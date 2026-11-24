package dev.caraalink.link

import dev.caraalink.diagnostics.DiagnosticLogger

/**
 * Answers the car's commands on port 5903.
 *
 * Some cars connect through USB debugging (ADB). The first thing they do is open port 5903 and send short
 * commands, waiting for an answer to each one. Numbers are 4 bytes. A text string is a 4-byte length followed
 * by the text. These answers match what the official CarbitLink app sends.
 *
 * What the car asks, and what we answer:
 *  - 0x8000  "Are you still there?", about every 2 seconds. We must answer, or the car says the app is not
 *            detected and drops the screen after about 16 seconds. Answer: 1 if the phone screen is on
 *            (-1 if off), then 0.
 *  - 0x09    The app version. Answer: 574, the version the official app reports.
 *  - 0x8001  The address of a web server. Answer: empty.
 *  - 0x803D  An update check. The car sends details about itself. Answer: "noupdate".
 *  - 0x8031  The car says which app it is using. Answer: 0.
 *  - 0x08    The car asks us to fetch a web page. We never do. Answer: "REQUEST ERROR!".
 *
 * Any other command is logged and ignored. We don't know how long it is, so we can't read past it.
 */
class LegacyControlSession(
    private val log: (String) -> Unit,
    /** Says whether the phone's screen is on. The car asks this in its "are you still there?" check. */
    private val screenOn: () -> Boolean = { true },
) {

    private var buf = ByteArray(0)
    private var broken = false

    /** Give it the bytes received from the car. It returns the replies to send, in order. */
    fun feed(data: ByteArray): List<ByteArray> {
        if (broken) return emptyList()
        buf += data
        val replies = ArrayList<ByteArray>()
        while (!broken && buf.size >= 4) {
            val reader = Reader(buf)
            try {
                val command = reader.int()
                val reply = handle(command, reader)
                if (broken) break
                buf = buf.copyOfRange(reader.pos, buf.size)
                if (reply != null) replies += reply
            } catch (_: NeedMore) {
                break
            } catch (e: Malformed) {
                log("malformed command data: ${e.message}")
                broken = true
            }
        }
        return replies
    }

    private fun handle(command: Int, r: Reader): ByteArray? = when (command) {
        CMD_HEARTBEAT -> {
            val screen = if (screenOn()) 1 else -1
            log("CMD ${hex(command)} heartbeat -> screen=$screen transfer=0")
            int(screen) + int(0)
        }
        CMD_APP_VERSION -> {
            log("CMD ${hex(command)} get app version -> $OFFICIAL_VERSION_CODE")
            int(OFFICIAL_VERSION_CODE)
        }
        CMD_ENVIRONMENT -> {
            log("CMD ${hex(command)} get environment -> (empty string)")
            string("")
        }
        CMD_CHECK_UPDATE -> {
            val dataLength = r.int()
            val osType = r.int()
            val machineId = r.int()
            val versionCode = r.int()
            val versionName = r.string()
            val packageName = r.string()
            log(
                "CMD ${hex(command)} check update: dataLength=$dataLength osType=$osType machineId=$machineId " +
                    "versionCode=$versionCode versionName=$versionName packageName=$packageName -> noupdate",
            )
            string("noupdate")
        }
        CMD_USING_CARMAN -> {
            val dataLength = r.int()
            val carmanPackage = r.string()
            val a = r.int()
            val channel = r.int()
            val b = r.int()
            val c = r.int()
            log("CMD ${hex(command)} using carman: dataLength=$dataLength package=$carmanPackage ints=[$a, $channel, $b, $c] -> 0")
            int(0)
        }
        CMD_HTTP_RESPONSE -> {
            val url = r.string()
            log("CMD ${hex(command)} http request (not made, answering REQUEST ERROR!): ${url.take(120)}${if (url.length > 120) "..." else ""} (${url.length} chars)")
            string(REQUEST_ERROR)
        }
        else -> {
            log("CMD ${hex(command)} UNKNOWN, not answered; following bytes: ${r.remainingHex()}")
            broken = true
            null
        }
    }

    private object NeedMore : Exception() {
        override fun fillInStackTrace(): Throwable = this
    }

    private class Malformed(message: String) : Exception(message)

    private class Reader(private val b: ByteArray) {
        var pos = 0
            private set

        fun int(): Int {
            if (pos + 4 > b.size) throw NeedMore
            val v = ((b[pos].toInt() and 0xFF) shl 24) or ((b[pos + 1].toInt() and 0xFF) shl 16) or
                ((b[pos + 2].toInt() and 0xFF) shl 8) or (b[pos + 3].toInt() and 0xFF)
            pos += 4
            return v
        }

        fun string(): String {
            val length = int()
            if (length < 0 || length > MAX_STRING) throw Malformed("string length $length")
            if (pos + length > b.size) throw NeedMore
            val s = String(b, pos, length, Charsets.UTF_8)
            pos += length
            return s
        }

        fun remainingHex(): String {
            val n = minOf(b.size - pos, 64)
            val sb = StringBuilder()
            for (i in 0 until n) sb.append(String.format("%02X ", b[pos + i]))
            return sb.toString().trim() + if (b.size - pos > n) " ... (${b.size - pos} bytes)" else ""
        }
    }

    companion object {
        private fun hex(command: Int) = "0x" + Integer.toHexString(command).uppercase()

        const val PORT = 5903
        private const val CMD_HEARTBEAT = 0x8000
        private const val CMD_APP_VERSION = 0x09
        private const val CMD_HTTP_RESPONSE = 0x08
        private const val CMD_ENVIRONMENT = 0x8001
        private const val CMD_USING_CARMAN = 0x8031
        private const val CMD_CHECK_UPDATE = 0x803D
        private const val MAX_STRING = 10240
        private const val REQUEST_ERROR = "REQUEST ERROR!"

        /** The version number the official CarbitLink 6.6.0 app gives when asked. The car may need at least that. */
        const val OFFICIAL_VERSION_CODE = 574

        internal fun int(value: Int): ByteArray =
            byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())

        internal fun string(value: String): ByteArray {
            val bytes = value.toByteArray(Charsets.UTF_8)
            return int(bytes.size) + bytes
        }
    }
}

/** Port 5903: gives each connection's bytes to its own [LegacyControlSession]. */
class LegacyControlHandler(
    private val logger: DiagnosticLogger,
    private val screenOn: () -> Boolean = { true },
) : AdbTransport.StreamHandler {

    private val sessions = HashMap<Int, LegacyControlSession>()

    override fun onOpen(stream: MirrorStream) {
        val id = stream.localId
        sessions[id] = LegacyControlSession({ logger.log("5903 #$id $it") }, screenOn)
    }

    override fun onClose(stream: MirrorStream) {
        sessions.remove(stream.localId)
    }

    override fun onData(stream: MirrorStream, data: ByteArray) {
        sessions[stream.localId]?.feed(data)?.forEach { stream.write(it) }
    }
}
