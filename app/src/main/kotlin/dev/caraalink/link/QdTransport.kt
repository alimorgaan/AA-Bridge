package dev.caraalink.link

import android.content.res.Resources
import android.os.Build
import android.os.Process
import android.view.MotionEvent
import dev.caraalink.diagnostics.DiagnosticLogger
import dev.caraalink.usb.UsbAccessoryConnection
import org.json.JSONObject
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** QDLink (Neusoft) wire format. Layouts: research_notes/QDLink/qdlink_protocol.md. */
object QdFrames {
    const val CHUNK = 512
    const val HEADER = 16
    const val MAGIC = 0x35413541 // "5A5A"
    const val TYPE_CMD = 0
    const val TYPE_VIDEO = 1
    const val TYPE_TOUCH = 2
    const val TYPE_APP = 13
    const val FORMAT_JSON = 1
    const val FORMAT_H264 = 2

    /** Header + ext + payload, zero-padded to a multiple of 512. totalSize excludes the padding. */
    fun frame(msgType: Int, payloadFormat: Int, payload: ByteArray, ext: ByteArray = ByteArray(0)): ByteArray {
        val total = HEADER + ext.size + payload.size
        return ByteBuffer.allocate((total + CHUNK - 1) / CHUNK * CHUNK)
            .putInt(MAGIC).putInt(total).putShort(ext.size.toShort())
            .put(msgType.toByte()).put(0).put(0).put(payloadFormat.toByte()).put(0).put(0)
            .put(ext).put(payload).array()
    }

    fun json(msgType: Int, text: String) = frame(msgType, FORMAT_JSON, text.toByteArray())

    /** Screen capture extension header: landscape (90), in-app presentation, the car's VIDEO_ARGS echoed. */
    fun videoExt(w: Int, h: Int, encodingType: Int, fps: Int, bitRate: Int, interval: Int): ByteArray =
        ByteBuffer.allocate(32).putShort(32).put(1).put(0).putInt(w).putInt(h).putShort(90).put(1)
            .put(encodingType.toByte()).putInt(fps).putInt(bitRate).putInt(interval).put(1).array()

    /** The old-format AppStatus QDLink sends once before anything else. */
    fun appStatus(sdk: Int): ByteArray = ByteBuffer.allocate(CHUNK).apply {
        put("!BIN".toByteArray()).putInt(0).putInt(512).putInt(512).putInt(64).putInt(128).putInt(128).putInt(2)
        for (i in 0 until 32) put((i + 32).toByte())
        putInt(0).putInt(1).putInt(1).putInt(sdk).putInt(2)
    }.array()

    fun nalType(au: ByteArray): Int {
        val i = if (au.size > 3 && au[2].toInt() == 1) 3 else 4
        return if (au.size > i) au[i].toInt() and 0x1F else -1
    }
}

/** QDLink's older `!BIN` format: 512-byte big-endian blocks, A6A6 text in HuMsg blocks. Layouts: QDLink 1.5.0. */
object QdBin {
    const val SIZE = 512
    const val TYPE_CMD = 0
    const val TYPE_SCREEN = 1
    const val TYPE_HU = 3
    const val TYPE_TOUCH = 10
    const val TYPE_SPEECH = 12
    const val CMD_VERSION = 3
    const val CMD_LAND_MODE = 5
    const val CMD_HEARTBEAT = 10
    const val CMD_PLAY_STATUS = 12
    const val CMD_UPGRADE = 13
    const val CMD_CAPTURE_SUPPORT = 16
    const val CMD_KEY_FRAME = 17
    const val ACTION_ENCODER_ARGS = 18
    /** The SSP JSON for int 1. */
    const val INT_1 = """{"D":0,"T":"i","V":1}"""

    fun isBin(b: ByteArray, off: Int = 0) = b.size - off >= 4 && b[off] == 0x21.toByte() &&
        b[off + 1] == 0x42.toByte() && b[off + 2] == 0x49.toByte() && b[off + 3] == 0x4E.toByte()

    /** Bytes after the block: a car HuMsg carries its A6A6 text, speech its PCM. */
    fun extraLength(block: ByteArray): Int {
        val bb = ByteBuffer.wrap(block)
        return when (bb.getInt(4)) {
            TYPE_HU -> if (bb.getInt(28) == 1) bb.getInt(8) - bb.getInt(12) else 0
            TYPE_SPEECH -> bb.getInt(8) - SIZE
            else -> 0
        }
    }

    /** A new phone block: magic, sizes, [action] and the phone's mark 0x20..0x3F. */
    fun block(type: Int, action: Int, requestSize: Int = 128, responseSize: Int = 128): ByteBuffer =
        ByteBuffer.allocate(SIZE).apply {
            put("!BIN".toByteArray()).putInt(type).putInt(SIZE).putInt(SIZE).putInt(64).putInt(requestSize).putInt(responseSize).putInt(action)
            for (i in 0 until 32) put((i + 32).toByte())
        }

    /** QDLink answers a car request with its first [keep] bytes and the rest zeroed, then fills 192 on. */
    fun reply(request: ByteArray, keep: Int): ByteBuffer = ByteBuffer.wrap(request.copyOf(keep).copyOf(SIZE))

    fun upgrade(): ByteArray = block(TYPE_CMD, 2).putInt(68, CMD_UPGRADE).putInt(72, 5).array()

    fun speech(): ByteArray = block(TYPE_SPEECH, 0, 16, 16).putInt(64, 1).putInt(68, 16000).putInt(72, 1).putInt(76, 16).array()

    /** Phone HuMsg: the block, then [text] at 512 padded to 512. */
    fun huMsg(text: ByteArray): ByteArray {
        val total = SIZE + (text.size + SIZE - 1) / SIZE * SIZE
        return ByteBuffer.allocate(total).put(block(TYPE_HU, 2, 64, 0).putInt(8, total).putInt(64, text.size).array()).put(text).array()
    }

    /** `A6A6`, flow 00, total length, app and logic ids and each arg behind hex lengths, then CRC-16/XMODEM. */
    fun a6a6(logic: String, vararg args: String): ByteArray {
        val body = StringBuilder().append(hex(APP.length, 2)).append(APP).append(hex(logic.length, 2)).append(logic).append(hex(args.size, 2))
        args.forEach { body.append(hex(it.toByteArray().size, 8)).append(it) }
        val text = "A6A600" + hex(14 + body.toString().toByteArray().size + 4, 8) + body
        return (text + hex(crc16(text.toByteArray()), 4)).toByteArray()
    }

    /** The logic id of an A6A6 message ("HUINFO", "BTADDRESS", "heartbeat"), or null. */
    fun logicId(p: ByteArray): String? {
        val s = String(p, Charsets.ISO_8859_1)
        if (!s.startsWith("A6A6")) return null
        return runCatching {
            val i = 16 + s.substring(14, 16).toInt(16)
            s.substring(i + 2, i + 2 + s.substring(i, i + 2).toInt(16))
        }.getOrNull()
    }

    fun crc16(b: ByteArray): Int {
        var crc = 0
        for (x in b) {
            crc = crc xor ((x.toInt() and 0xFF) shl 8)
            repeat(8) { crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1 }
        }
        return crc and 0xFFFF
    }

    // Locale-free: String.format can print Arabic digits.
    private fun hex(v: Int, width: Int) = v.toString(16).padStart(width, '0')

    private const val APP = "QDRIVE_ASSISTANT"
}

/**
 * Reassembles car frames from 512-byte reads: 5A5A frames and `!BIN` blocks with their payload.
 * Zeros between frames are padding, anything else before a magic is reported as junk.
 */
class QdReader(
    private val onFrame: (msgType: Int, payloadFormat: Int, payload: ByteArray) -> Unit,
    private val onBin: (block: ByteArray, extra: ByteArray) -> Unit,
    private val onJunk: (ByteArray) -> Unit,
) {
    private var buf = ByteArray(0)

    fun feed(data: ByteArray, len: Int) {
        buf += data.copyOf(len)
        while (true) {
            var start = 0
            while (start < buf.size && buf[start] == 0.toByte()) start++
            val magic = indexOfMagic(start)
            if (magic < 0) {
                if (start < buf.size) onJunk(buf.copyOfRange(start, buf.size))
                buf = ByteArray(0)
                return
            }
            if (magic > start) onJunk(buf.copyOfRange(start, magic))
            buf = buf.copyOfRange(magic, buf.size)
            if (QdBin.isBin(buf)) {
                if (buf.size < QdBin.SIZE) return
                val extra = QdBin.extraLength(buf)
                if (extra < 0 || extra > MAX_FRAME) {
                    onJunk(buf.copyOf(QdFrames.HEADER))
                    buf = buf.copyOfRange(4, buf.size)
                    continue
                }
                if (buf.size < QdBin.SIZE + extra) return
                onBin(buf.copyOf(QdBin.SIZE), buf.copyOfRange(QdBin.SIZE, QdBin.SIZE + extra))
                buf = buf.copyOfRange(QdBin.SIZE + extra, buf.size)
                continue
            }
            if (buf.size < QdFrames.HEADER) return
            val bb = ByteBuffer.wrap(buf)
            val total = bb.getInt(4)
            val ext = bb.getShort(8).toInt() and 0xFFFF
            if (total < QdFrames.HEADER + ext || total > MAX_FRAME) {
                onJunk(buf.copyOf(QdFrames.HEADER))
                buf = buf.copyOfRange(4, buf.size)
                continue
            }
            if (buf.size < total) return
            onFrame(buf[10].toInt() and 0xFF, buf[13].toInt() and 0xFF, buf.copyOfRange(QdFrames.HEADER + ext, total))
            buf = buf.copyOfRange(total, buf.size)
        }
    }

    private fun indexOfMagic(from: Int): Int {
        for (i in from..buf.size - 4) {
            if (buf[i] == 0x35.toByte() && buf[i + 1] == 0x41.toByte() && buf[i + 2] == 0x35.toByte() && buf[i + 3] == 0x41.toByte()) return i
            if (QdBin.isBin(buf, i)) return i
        }
        return -1
    }

    companion object {
        private const val MAX_FRAME = 4 * 1024 * 1024
    }
}

/**
 * Spike: QDLink's cable protocol on the accessory fd, feeding the same [MirrorHost]
 * as CarbitLink. Logs every car message so a remote tester's drive shows the real handshake.
 */
class QdTransport(
    private val conn: UsbAccessoryConnection,
    private val logger: DiagnosticLogger,
    private val host: MirrorHost,
    private val onClosed: (String) -> Unit,
) : CarTransport {

    private val queue = LinkedBlockingQueue<Queued>()
    private val backlog = AtomicInteger()
    private val closed = AtomicBoolean()
    private var writer: Thread? = null
    private var timer: Thread? = null
    private val startedAt = System.currentTimeMillis()
    @Volatile private var lastReadAt = startedAt
    @Volatile private var reads = 0
    @Volatile private var writeSince = 0L
    @Volatile private var carInfo = false
    /** The car's first frame was 5A5A. */
    private var json = false
    private var appStatusResends = 0
    private var appStatusAt = startedAt
    @Volatile private var whitelist = false
    private var whitelistWanted = false
    private var carW = 800
    private var carH = 480
    private var encodingType = 3
    private var fps = 0
    private var bitRate = 0
    private var interval = 0
    private var configured = false
    /** The car speaks the old `!BIN` format; QDLink then drops its JSON heartbeat. */
    @Volatile private var bin = false
    /** The car's screen capture request: QDLink sends it back, amended, as every video header. */
    @Volatile private var binScreen: ByteArray? = null
    private var binHeartbeats = 0
    private var binOther = 0
    private var touches = 0
    @Volatile private var framesSent = 0L
    @Volatile private var videoBytes = 0L
    private val video = VideoStream()

    fun start() {
        val a = conn.accessory
        logger.log("QD accessory: manufacturer=${a.manufacturer} model=${a.model} version=${a.version} " +
            "description=${a.description} uri=${a.uri} serial=${runCatching { a.serial }.getOrNull()}")
        writer = thread(name = "QDLink-writer", isDaemon = true) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
            writeLoop()
        }
        logger.log("QD TX: AppStatus (!BIN)")
        send(QdFrames.appStatus(Build.VERSION.SDK_INT))
        thread(name = "QDLink-reader", isDaemon = true) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
            readLoop()
        }
        timer = thread(name = "QDLink-timer", isDaemon = true) { timerLoop() }
    }

    override fun stop(reason: String) {
        if (!closed.compareAndSet(false, true)) return
        logger.log("QD session closed: $reason (reads=$reads videoFrames=$framesSent)")
        writer?.interrupt()
        timer?.interrupt()
        conn.close()
        onClosed(reason)
    }

    private fun send(frame: ByteArray, video: Boolean = false) {
        if (closed.get()) return
        backlog.addAndGet(frame.size)
        queue.put(Queued(frame, video))
    }

    private fun sendJson(msgType: Int, text: String, log: Boolean = true) {
        if (log) logger.log("QD TX: $text")
        send(QdFrames.json(msgType, text))
    }

    private fun sendCmd(name: String, para: JSONObject? = null) =
        sendJson(QdFrames.TYPE_CMD, JSONObject().put("CMD", name).apply { if (para != null) put("PARA", para) }.toString())

    private fun writeLoop() {
        try {
            while (!closed.get()) {
                val f = queue.take()
                writeSince = System.currentTimeMillis()
                conn.output.write(f.bytes)
                writeSince = 0L
                backlog.addAndGet(-f.bytes.size)
            }
        } catch (_: InterruptedException) {
        } catch (e: IOException) {
            stop("write failed: ${e.message}")
        }
    }

    private fun readLoop() {
        val buf = ByteArray(QdFrames.CHUNK)
        val reader = QdReader(::onFrame, ::onBin) { junk -> logger.logBytes("QD RX junk", junk, minOf(junk.size, 64)) }
        try {
            while (!closed.get()) {
                val n = conn.input.read(buf)
                if (n < 0) {
                    stop("read failed: end of stream")
                    return
                }
                if (n == 0) continue
                lastReadAt = System.currentTimeMillis()
                if (++reads <= RAW_READS_LOGGED) logger.logBytes("QD RX raw #$reads", buf, n)
                reader.feed(buf, n)
            }
        } catch (e: IOException) {
            stop("read failed: ${e.message}")
        }
    }

    private fun timerLoop() {
        var tick = 0
        var silent = false
        try {
            while (!closed.get()) {
                Thread.sleep(1000)
                tick++
                // QDLink: heartbeat every 3 s, the first after 1 s, before the car has said anything.
                if (tick % 3 == 1 && !bin) sendJson(QdFrames.TYPE_CMD, """{"CMD":"HEARTBEAT"}""", log = tick == 1)
                if (whitelist) {
                    if (bin) sendHu("LEGAL_APP_ON", QdBin.INT_1, log = false)
                    else sendJson(QdFrames.TYPE_APP, WHITELIST_ON, log = false)
                }
                val silentMs = System.currentTimeMillis() - lastReadAt
                if (silentMs >= SILENCE_MS && !silent) {
                    silent = true
                    logger.log("QD: no bytes from the car for ${silentMs / 1000}s (reads=$reads)")
                } else if (silentMs < SILENCE_MS && silent) {
                    silent = false
                    logger.log("QD: the car is sending again")
                }
                if (tick % 10 == 0) logger.log("QD stats: reads=$reads videoFrames=$framesSent videoBytes=$videoBytes backlog=${backlog.get()}")
                watchdog(System.currentTimeMillis(), startedAt, lastReadAt, reads > 0, carInfo, writeSince)?.let {
                    stop(it)
                    return
                }
            }
        } catch (_: InterruptedException) {
        }
    }

    private fun onFrame(msgType: Int, payloadFormat: Int, payload: ByteArray) {
        json = true
        when {
            payloadFormat == QdFrames.FORMAT_JSON -> {
                val text = String(payload)
                logger.log("QD RX type=$msgType: $text")
                if (msgType == QdFrames.TYPE_CMD) {
                    runCatching { onCommand(JSONObject(text)) }.onFailure { logger.log("QD command failed: $it") }
                }
            }
            msgType == QdFrames.TYPE_TOUCH -> onTouch(payload)
            else -> logger.logBytes("QD RX type=$msgType format=$payloadFormat size=${payload.size}", payload, minOf(payload.size, 64))
        }
    }

    private fun onCommand(msg: JSONObject) {
        val para = msg.optJSONObject("PARA") ?: JSONObject()
        when (msg.optString("CMD")) {
            "HEARTBEAT" -> if (!carInfo) resendAppStatus()
            "CAR_INFO" -> {
                carInfo = true
                val w = para.optInt("CarWidth")
                val h = para.optInt("CarHeight")
                if (w > 0 || h > 0) {
                    carW = even(maxOf(w, h))
                    carH = even(minOf(w, h))
                }
                whitelistWanted = para.optJSONObject("CarFeature")?.optInt("legal_app_watch") == 1
                sendCmd("PHONE_INFO", phoneInfo(w, h, para.optInt("MirrorTypeReq")))
                sendCmd("UPDATE_NOTIFY", JSONObject().put("UpdateStatus", 5))
            }
            "VIDEO_SUP_REQ" -> {
                sendCmd("VIDEO_SUP_RSP", JSONObject().put("VideoFormat", 3).put("VideoSupport", 1))
                whitelist = whitelistWanted
            }
            "VIDEO_ARGS" -> {
                encodingType = para.optInt("EncodingType").takeIf { it > 0 } ?: 3
                fps = para.optInt("FrameRate")
                bitRate = para.optInt("BitRate")
                interval = para.optInt("FrameInterval")
                sendCmd("SPEECH_ARGS", JSONObject().put("EncodingType", 1).put("SampleRate", 16000)
                    .put("ChannelConfig", 1).put("AudioFormat", 16))
                configure()
            }
            "VIDEO_CTRL" -> if (para.optInt("PlayStatus") == 1) {
                if (!configured) configure()
                host.onDataStreamOpen(video)
            }
            "KEY_FRAME_REQ" -> host.onStartVideo()
            "LAND_MODE_REQ" -> sendCmd("LAND_MODE_RSP", JSONObject().put("Orientation", para.optInt("Orientation"))
                .put("Authority", 1).put("StatusArg", 0))
        }
    }

    /** An old-format car: Version, capture support, the capture request and PlayStatus lead to video. */
    private fun onBin(b: ByteArray, extra: ByteArray) {
        if (!bin) {
            // QDLink keeps to the format of the car's first frame.
            if (json) return logger.logBytes("QD RX !BIN in a 5A5A session", b, 96)
            bin = true
            carInfo = true // Version stands in for CAR_INFO; no handshake timeout.
            logger.log("QD: the car speaks the old !BIN format")
        }
        val bb = ByteBuffer.wrap(b)
        val type = bb.getInt(4)
        val action = bb.getInt(28)
        val cmd = bb.getInt(68)
        val value = bb.getInt(72)
        when {
            type == QdBin.TYPE_CMD && action == 1 && cmd == QdBin.CMD_HEARTBEAT -> {
                if (binHeartbeats++ < 2) logger.log("QD RX !BIN heartbeat value=$value")
                // QDLink 1.9.8 answers with ret 1.
                if (value == 1) send(QdBin.reply(b, 76).putInt(192, 1).array())
            }
            type == QdBin.TYPE_CMD && action == 1 -> {
                logger.log("QD RX !BIN cmd=$cmd value=$value")
                onBinCommand(b, cmd, value)
            }
            type == QdBin.TYPE_SCREEN && action == QdBin.ACTION_ENCODER_ARGS -> {
                fps = bb.getInt(64)
                bitRate = bb.getInt(68)
                interval = bb.getInt(72)
                configure()
            }
            type == QdBin.TYPE_SCREEN -> onScreenRequest(b)
            type == QdBin.TYPE_HU && action == 1 -> onHuMsg(extra.copyOf(minOf(maxOf(bb.getInt(64), 0), extra.size)))
            // Plain acknowledgement of one of our HuMsgs; one that says more is logged below.
            type == QdBin.TYPE_HU && (64 until b.size).all { b[it].toInt() == 0 } -> Unit
            type == QdBin.TYPE_TOUCH -> onBinTouch(bb)
            else -> if (binOther++ < 60) logger.logBytes("QD RX !BIN type=$type action=$action cmd=$cmd", b, 208)
        }
    }

    private fun onBinCommand(b: ByteArray, cmd: Int, value: Int) {
        val bb = ByteBuffer.wrap(b)
        when (cmd) {
            QdBin.CMD_VERSION -> {
                lastCapture = null // A full handshake: the car will ask again.
                val w = bb.getInt(128)
                val h = bb.getInt(132)
                logger.log("QD car: ${w}x$h version='${String(b, 72, 32).trim('\u0000', ' ')}' type='${String(b, 104, 16).trim('\u0000', ' ')}'")
                if (w > 0 && h > 0) {
                    carW = even(maxOf(w, h))
                    carH = even(minOf(w, h))
                }
                val (long, short, mw, mh) = phoneSizes()
                send(QdBin.reply(b, 72).putInt(192, Build.VERSION.SDK_INT).putInt(196, long).putInt(200, short)
                    .putInt(204, mw).putInt(208, mh).array())
                send(QdBin.upgrade())
                logger.log("QD TX: Version reply + Upgrade")
            }
            QdBin.CMD_CAPTURE_SUPPORT -> {
                // QDLink says yes only once the user accepts the capture dialog, so never at once.
                thread(name = "QDLink-consent", isDaemon = true) {
                    Thread.sleep(CONSENT_MS)
                    if (closed.get()) return@thread
                    send(QdBin.reply(b, 72).putInt(192, 1).array())
                    logger.log("QD TX: MirrorSupport ret=1")
                }
                // QDLink's pauses, also on its reader thread: they set the order of all its answers.
                Thread.sleep(300)
                sendHu("phoneready")
                Thread.sleep(300)
                sendHu("phonereadynew")
            }
            QdBin.CMD_PLAY_STATUS -> if (value == 1) {
                if (!configured) {
                    if (binScreen == null) lastCapture?.let { c ->
                        logger.log("QD: the car skipped its capture request, using its last one")
                        useScreen(c.screen)
                        fps = c.fps
                        bitRate = c.bitRate
                        interval = c.interval
                    }
                    configure()
                }
                host.onDataStreamOpen(video)
            }
            QdBin.CMD_KEY_FRAME -> host.onStartVideo()
            QdBin.CMD_LAND_MODE -> {
                send(QdBin.reply(b, 76).putInt(192, value).putInt(196, 1).array())
                logger.log("QD TX: LandMode $value ok")
            }
        }
    }

    /** The car's screen capture request: size at 64/68, encoding at 80, rate at 180/184/188. */
    private fun onScreenRequest(b: ByteArray) {
        logger.logBytes("QD RX !BIN screen request", b, 208)
        useScreen(b)
        send(QdBin.speech())
    }

    private fun useScreen(b: ByteArray) {
        binScreen = b
        val bb = ByteBuffer.wrap(b)
        val w = bb.getInt(64)
        val h = bb.getInt(68)
        if (w > 0 && h > 0) {
            carW = even(maxOf(w, h))
            carH = even(minOf(w, h))
        }
        encodingType = bb.getInt(80).takeIf { it > 0 } ?: 3
        fps = bb.getInt(180)
        bitRate = bb.getInt(184)
        interval = bb.getInt(188)
    }

    private fun onHuMsg(p: ByteArray) {
        val logic = QdBin.logicId(p)
        if (logic != "heartbeat") logger.log("QD RX A6A6: ${String(p)}")
        when (logic) {
            "HUINFO" -> {
                Thread.sleep(500)
                sendHu("phoneinitok")
                sendHu("NEEDUPGRADE", QdBin.INT_1) // No update for the car's link app.
                // QDLink starts its 1 s LEGAL_APP_ON timer here when the car watches apps.
                whitelist = Regex("legal_app_watch\\W+1").containsMatchIn(String(p))
            }
            "BTADDRESS" -> sendHu("BTADDRESS", """{"D":0,"T":"s","V":"00:00:00:00:00:00"}""")
            // ponytail: always "this phone is on the car's Bluetooth"; QDLink checks A2DP, which needs BLUETOOTH_CONNECT.
            "CARBTADDRESS" -> sendHu("COMPARE_OK")
        }
    }

    /** InputControl: int16 event 0x8000 down, 0x8001 move, else up, at 66; x, y at 68/70. */
    private fun onBinTouch(bb: ByteBuffer) {
        val event = bb.getShort(66).toInt() and 0xFFFF
        val x = bb.getShort(68).toInt()
        val y = bb.getShort(70).toInt()
        if (touches++ < TOUCHES_LOGGED) logger.log("QD touch event=0x${event.toString(16)} count=${bb.getShort(64)} x=$x y=$y")
        val ev = when (event) {
            0x8000 -> MotionEvent.ACTION_DOWN
            0x8001 -> MotionEvent.ACTION_MOVE
            else -> MotionEvent.ACTION_UP
        }
        host.onTouch(ev, x, y)
    }

    private fun sendHu(logic: String, vararg args: String, log: Boolean = true) {
        if (log) logger.log("QD TX A6A6: $logic ${args.joinToString()}")
        send(QdBin.huMsg(QdBin.a6a6(logic, *args)))
    }

    /** The car is alive but missed our AppStatus: send it again, a few times at most. */
    private fun resendAppStatus() {
        val now = System.currentTimeMillis()
        if (appStatusResends >= APP_STATUS_RESENDS || now - appStatusAt < APP_STATUS_GAP_MS) return
        appStatusResends++
        appStatusAt = now
        logger.log("QD TX: AppStatus again ($appStatusResends/$APP_STATUS_RESENDS), the car sends heartbeats but no CAR_INFO")
        send(QdFrames.appStatus(Build.VERSION.SDK_INT))
    }

    private fun configure() {
        configured = true
        binScreen?.let { lastCapture = Capture(it, fps, bitRate, interval) }
        logger.log("QD video: ${carW}x$carH fps=${videoFps()} bitRate=${videoBitRate()} (car asked fps=$fps bitRate=$bitRate interval=$interval type=$encodingType)")
        host.onCaptureConfig(CaptureConfig(carW, carH, videoFps(), 1, videoBitRate(), 0, 0, 0, align = 2))
    }

    // QDLink 1.9.8's defaults when the car asks for nothing.
    private fun videoFps() = fps.takeIf { it > 0 } ?: 24
    private fun videoBitRate() = bitRate.takeIf { it > 0 } ?: 4_000_000

    /** Phone long and short side, and the car-shaped mirror area that fits in them. */
    private fun phoneSizes(): IntArray {
        val dm = Resources.getSystem().displayMetrics
        val long = maxOf(dm.widthPixels, dm.heightPixels)
        val short = minOf(dm.widthPixels, dm.heightPixels)
        val phoneAspect = long.toFloat() / short
        val (mw, mh) = if (carW.toFloat() / carH > phoneAspect) even((carH * phoneAspect).toInt()) to carH
            else carW to even((carW / phoneAspect).toInt())
        return intArrayOf(long, short, mw, mh)
    }

    private fun phoneInfo(w: Int, h: Int, mirrorType: Int): JSONObject {
        val (long, short, mw, mh) = phoneSizes()
        return JSONObject().put("PhoneWidth", long).put("PhoneHeight", short).put("MirrorWidth", mw).put("MirrorHeight", mh)
            .put("PhoneWidthInApp", w).put("PhoneHeightInApp", h).put("MirrorWidthInApp", w).put("MirrorHeightInApp", h)
            .put("MirrorTypeSupport", mirrorType).put("Platform", 0).put("PlatformVersion", Build.VERSION.SDK_INT.toString())
            .put("Version", QDLINK_VERSION).put("PhoneBrand", Build.MANUFACTURER).put("PhoneModel", Build.MODEL)
            .put("PhoneUUID", "").put("PhoneName", "").put("PhoneFeature", JSONObject().put("PassistMobileNum", ""))
            .put("PhoneSystemTime", 0)
    }

    private fun onTouch(p: ByteArray) {
        if (p.size < 15) {
            logger.logBytes("QD RX short touch", p, p.size)
            return
        }
        val bb = ByteBuffer.wrap(p)
        val action = bb.getInt(0)
        val fingerAction = p[6].toInt()
        val x = bb.getFloat(7)
        val y = bb.getFloat(11)
        if (touches++ < TOUCHES_LOGGED) logger.log("QD touch action=$action fingers=${p[4]} id=${p[5]} fingerAction=$fingerAction x=$x y=$y")
        // 1.9.8 reads the per-finger action (1 down, 2 up, 3 move); 1.4.4 passed the int as an Android action.
        val ev = when (fingerAction) {
            1 -> MotionEvent.ACTION_DOWN
            2 -> MotionEvent.ACTION_UP
            3 -> MotionEvent.ACTION_MOVE
            else -> action.takeIf { it in 0..2 } ?: return
        }
        host.onTouch(ev, x.toInt(), y.toInt())
    }

    /** CarSession hands over int32 LE length + Annex-B frames, as on CarbitLink's data channel. */
    private inner class VideoStream : MirrorStream {
        override val localId = 0
        override val closed get() = this@QdTransport.closed.get()
        private var parameterSets: ByteArray? = null

        override fun write(data: ByteArray) {
            val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            while (bb.remaining() >= 4) {
                val n = bb.int
                if (n <= 0 || n > bb.remaining()) return
                val au = ByteArray(n).also { bb.get(it) }
                val nal = QdFrames.nalType(au)
                if (nal == 7) {
                    parameterSets = au
                    continue
                }
                if (bin) {
                    // QDLink 1.5.0 sends SPS+PPS as their own frame; we repeat them before each key
                    // frame, which some encoders open with SEI or AUD.
                    if (nal != 1) parameterSets?.let { send(binFrame(it), video = true) }
                    send(binFrame(au), video = true)
                } else {
                    // QDLink 1.9.8 sends SPS+PPS ahead of every frame over USB.
                    parameterSets?.let { send(videoFrame(it), video = true) }
                    send(videoFrame(au), video = true)
                }
                framesSent++
                videoBytes += n
                if (framesSent <= 3) logger.log("QD video frame #$framesSent ($n bytes, nal ${QdFrames.nalType(au)})")
            }
        }

        private fun videoFrame(au: ByteArray) = QdFrames.frame(QdFrames.TYPE_VIDEO, QdFrames.FORMAT_H264, au,
            QdFrames.videoExt(carW, carH, encodingType, fps, bitRate, interval))

        /** The car's capture request up to 208, amended as QDLink's in-app capture does, then the AU padded to 512. */
        private fun binFrame(au: ByteArray): ByteArray {
            val total = QdBin.SIZE + (au.size + QdBin.SIZE - 1) / QdBin.SIZE * QdBin.SIZE
            val (long, short) = phoneSizes()
            val header = QdBin.reply(binScreen ?: QdBin.block(QdBin.TYPE_SCREEN, 0, 28, 88).array(), 208)
            val rate = header.getInt(180).takeIf { it > 0 } ?: 24
            val gop = header.getInt(188).takeIf { it > 0 } ?: 4
            header.putInt(8, total).putInt(92, au.size).putInt(100, long).putInt(104, short)
                .putInt(116, carW).putInt(120, carH).putInt(132, 3).putInt(136, 90).putInt(140, 1).putInt(148, 1920).putInt(152, 1080)
                .putInt(180, rate).putInt(188, gop)
                .putInt(192, rate).putInt(196, header.getInt(184)).putInt(200, gop).putInt(204, 1)
            if (framesSent == 0L) logger.logBytes("QD TX !BIN video header", header.array(), 208)
            return ByteBuffer.allocate(total).put(header.array()).put(au).array()
        }

        override fun backlogAgeMs() = queue.peek()?.ageMs() ?: 0L

        // Heartbeats and replies stay queued; only video is dropped.
        override fun clearBacklog() {
            queue.removeIf { q -> q.video.also { if (it) backlog.addAndGet(-q.bytes.size) } }
        }
    }

    private class Capture(val screen: ByteArray, val fps: Int, val bitRate: Int, val interval: Int)

    companion object {
        /** Reopened mid-session, the car goes straight to PlayStatus without its size and encoder args. */
        @Volatile private var lastCapture: Capture? = null

        const val MANUFACTURER = "Neusoft"
        private const val QDLINK_VERSION = "1.9.8"
        private const val RAW_READS_LOGGED = 12
        private const val TOUCHES_LOGGED = 200
        private const val SILENCE_MS = 5_000L
        private const val WHITELIST_ON = """{"AppID":"Mirror","FunctionID":"WhitelistAppOn","Para":{"WhitelistAppOn":1}}"""

        private const val CONSENT_MS = 2_500L
        private const val APP_STATUS_RESENDS = 3
        private const val APP_STATUS_GAP_MS = 2_500L
        // Timings from Shams-eldin's Emgrand Auto, tuned on QDLink cars.
        private const val SILENT_CLOSE_MS = 15_000L
        private const val STUCK_CLOSE_MS = 10_000L
        private const val STUCK_TALKING_CLOSE_MS = 20_000L
        private const val CAR_TALKING_MS = 5_000L
        private const val HANDSHAKE_MS = 12_000L

        /** Why to end a session the car stopped serving, so the service can reopen it; null while it's healthy. */
        internal fun watchdog(now: Long, startedAt: Long, lastReadAt: Long, carSpoke: Boolean, carInfo: Boolean, writeSince: Long): String? {
            val silent = now - lastReadAt
            val stuck = if (writeSince == 0L) 0L else now - writeSince
            return when {
                carSpoke && silent >= SILENT_CLOSE_MS -> "watchdog: no bytes from the car for ${silent / 1000}s"
                stuck >= STUCK_CLOSE_MS && (silent >= CAR_TALKING_MS || stuck >= STUCK_TALKING_CLOSE_MS) ->
                    "watchdog: a write has been stuck for ${stuck / 1000}s"
                !carInfo && now - startedAt >= HANDSHAKE_MS -> "watchdog: no CAR_INFO after ${HANDSHAKE_MS / 1000}s"
                else -> null
            }
        }

        private fun even(v: Int) = if (v % 2 != 0) v + 1 else v
    }
}
