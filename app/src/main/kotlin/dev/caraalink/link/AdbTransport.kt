package dev.caraalink.link

import android.util.Log
import dev.caraalink.diagnostics.DiagnosticLogger
import dev.caraalink.usb.UsbAccessoryConnection
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Device side of the ADB-over-AOA link the head unit speaks. Wire behaviour
 * matches what was confirmed from CarbitLink's libjadbd (claude/roadmap.md):
 * a 24-byte zero write before reading, jadbd's exact CNXN reply, and header
 * and payload sent as separate writes. Streams follow the public ADB spec:
 * OPEN -> OKAY(local, remote), WRTE needs an OKAY before the next WRTE.
 */
class AdbTransport(
    private val conn: UsbAccessoryConnection,
    private val logger: DiagnosticLogger,
    private val handlerFor: (service: String) -> StreamHandler?,
    private val onClosed: (reason: String) -> Unit,
) : CarTransport {

    interface StreamHandler {
        fun onOpen(stream: MirrorStream) {}
        fun onData(stream: MirrorStream, data: ByteArray)
        fun onClose(stream: MirrorStream) {}
    }

    inner class Stream(override val localId: Int, val remoteId: Int, val service: String, val handler: StreamHandler) : MirrorStream {
        private val queue = ArrayDeque<ByteArray>()
        private var canSend = true
        @Volatile override var closed = false
            private set
        private var queuedBytes = 0

        /** Queues [data] (split to maxdata chunks); sent one WRTE per remote OKAY. */
        override fun write(data: ByteArray) {
            if (closed || data.isEmpty()) return
            synchronized(this) {
                var off = 0
                while (off < data.size) {
                    val n = minOf(maxData, data.size - off)
                    queue.add(data.copyOfRange(off, off + n))
                    queuedBytes += n
                    off += n
                }
            }
            pump()
        }

        override fun backlogBytes(): Int = synchronized(this) { queuedBytes }

        override fun clearBacklog() = synchronized(this) {
            queue.clear()
            queuedBytes = 0
        }

        internal fun onRemoteOkay() {
            synchronized(this) { canSend = true }
            pump()
        }

        private fun pump() {
            val chunk = synchronized(this) {
                if (!canSend || closed) return
                val c = queue.poll() ?: return
                queuedBytes -= c.size
                canSend = false
                c
            }
            sendPacket(CMD_WRTE, localId, remoteId, chunk)
        }

        fun close() {
            if (closed) return
            closed = true
            sendPacket(CMD_CLSE, localId, remoteId, EMPTY)
            streams.remove(localId)
            handler.onClose(this)
        }

        internal fun closedByRemote() {
            if (closed) return
            closed = true
            streams.remove(localId)
            handler.onClose(this)
        }
    }

    private val running = AtomicBoolean(false)
    private val streams = ConcurrentHashMap<Int, Stream>()
    private val nextLocalId = AtomicInteger(1)
    private val writeLock = Any()
    @Volatile private var maxData = 4096
    private var readThread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        readThread = Thread({
            // One reversible experiment, not a confirmed fix (chatgpt-status.md
            // "External repo review for the EIO lead"): BLECarbitlink's own
            // protocol notes cite the *exact* EIO signature we keep hitting --
            // "a redundant set_configuration() on an already-configured
            // accessory makes the FIRST bulk write fail with EIO" -- from their
            // Linux host-side emulator, not proven against the real
            // CarbitLink dongle. A short settle delay before our own first
            // write can't fix a cause on CarbitLink's side, but if their host
            // stack is still finishing USB enumeration/configuration right as
            // we reopen (most likely right after our own reconnect retry),
            // writing the zero kick immediately is the worst possible timing
            // to land in that window. Cheap and reversible; revert if it
            // makes no measurable difference to EIO frequency.
            Thread.sleep(300)
            // Confirmed real (owner-captured log, 2026-11-09): when the
            // accessory has already vanished (physical USB-C disconnect at
            // the port level, not an app-layer issue), every one of
            // sendZeroKick()'s 50 attempts fails and it used to just return
            // silently -- readLoop() then entered anyway and blocked forever
            // on conn.input.read(), a plain FileInputStream over the raw fd
            // with no read timeout. That dead thread never called stop(),
            // so scheduleTransportReconnect() never ran again and the only
            // way out was force-quitting the app. Skip readLoop() entirely
            // when the handshake write itself never got through.
            if (sendZeroKick()) {
                readLoop()
            } else {
                // Phrasing matches UsbLinkForegroundService.isTransportIoFailure()
                // so this is treated as a retryable transport failure, not a
                // clean close -- otherwise this path would stop the service
                // outright instead of trying to reconnect.
                stop("write failed: device not responding after zero kick retries")
            }
        }, "AdbTransport").also { it.start() }
    }

    override fun stop(reason: String) {
        if (!running.compareAndSet(true, false)) return
        streams.values.toList().forEach { it.closedByRemote() }
        conn.close()
        readThread?.interrupt()
        logger.log("Transport stopped: $reason")
        onClosed(reason)
    }

    /** Returns false if every attempt failed -- caller must not enter [readLoop] on that fd. */
    private fun sendZeroKick(): Boolean {
        repeat(50) { attempt ->
            try {
                synchronized(writeLock) { conn.output.write(ByteArray(24)); conn.output.flush() }
                logger.log("zero kick sent (attempt ${attempt + 1}), waiting for CNXN")
                return true
            } catch (e: IOException) {
                logger.log("zero kick attempt ${attempt + 1} failed: ${e.message}")
                Thread.sleep(20)
            }
        }
        return false
    }

    private fun readLoop() {
        // 16 KB = the accessory driver's bulk buffer; the probe that worked live used this size.
        val buffer = ByteArray(16 * 1024)
        var pending = ByteArray(0)
        var reason = "eof"
        try {
            while (running.get()) {
                val n = conn.input.read(buffer)
                if (n < 0) break
                if (n == 0) continue
                pending += buffer.copyOfRange(0, n)
                while (true) {
                    while (pending.size >= 24 && !headerValid(pending)) pending = pending.copyOfRange(1, pending.size)
                    if (pending.size < 24) break
                    val hb = ByteBuffer.wrap(pending, 0, 24).order(ByteOrder.LITTLE_ENDIAN)
                    val len = hb.getInt(12)
                    if (len < 0 || pending.size < 24 + len) break
                    val payload = pending.copyOfRange(24, 24 + len)
                    handlePacket(hb.getInt(0), hb.getInt(4), hb.getInt(8), payload)
                    pending = pending.copyOfRange(24 + len, pending.size)
                }
            }
        } catch (e: IOException) {
            reason = e.message ?: "io error"
        } catch (e: Exception) {
            Log.e(TAG, "readLoop crashed", e)
            reason = "crash: ${e.message}"
        }
        stop(reason)
    }

    private fun handlePacket(cmd: Int, arg0: Int, arg1: Int, payload: ByteArray) {
        when (cmd) {
            CMD_CNXN -> {
                // Values per libjadbd TransportBase::handleConn (claude/roadmap.md).
                val isNew = (arg0 == VERSION || arg0 == VERSION_SKIP_CHECKSUM) && arg1 != 0x1000
                maxData = if (isNew) arg1 else 4096
                logger.log("CNXN from head unit v=0x${Integer.toHexString(arg0)} maxdata=$arg1 '${ascii(payload)}'")
                // Old streams belong to the previous connection.
                streams.values.toList().forEach { it.closedByRemote() }
                sendPacket(CMD_CNXN, if (isNew) VERSION_SKIP_CHECKSUM else VERSION, maxData, BANNER)
            }
            CMD_OPEN -> {
                val service = ascii(payload).trimEnd('\u0000')
                val handler = handlerFor(service)
                if (handler == null) {
                    logger.log("OPEN '$service' remote=$arg0 -> refused")
                    sendPacket(CMD_CLSE, 0, arg0, EMPTY)
                    return
                }
                val stream = Stream(nextLocalId.getAndIncrement(), arg0, service, handler)
                streams[stream.localId] = stream
                logger.log("OPEN '$service' remote=$arg0 -> local=${stream.localId}")
                sendPacket(CMD_OKAY, stream.localId, arg0, EMPTY)
                handler.onOpen(stream)
            }
            CMD_WRTE -> {
                val stream = streams[arg1]
                if (stream == null) {
                    sendPacket(CMD_CLSE, 0, arg0, EMPTY)
                    return
                }
                sendPacket(CMD_OKAY, stream.localId, stream.remoteId, EMPTY)
                try {
                    stream.handler.onData(stream, payload)
                } catch (e: Exception) {
                    Log.e(TAG, "handler for ${stream.service} threw", e)
                    logger.log("handler ${stream.service} threw: $e")
                }
            }
            CMD_OKAY -> streams[arg1]?.onRemoteOkay()
            CMD_CLSE -> {
                val stream = streams[arg1]
                if (stream != null) {
                    logger.log("CLSE '${stream.service}' local=${stream.localId}")
                    stream.closedByRemote()
                }
            }
            else -> logger.log("unhandled ADB cmd 0x${Integer.toHexString(cmd)}")
        }
    }

    private fun sendPacket(cmd: Int, arg0: Int, arg1: Int, payload: ByteArray) {
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(cmd).putInt(arg0).putInt(arg1).putInt(payload.size)
            .putInt(payload.sumOf { it.toInt() and 0xFF })
            .putInt(cmd xor -1).array()
        try {
            synchronized(writeLock) {
                conn.output.write(header)
                if (payload.isNotEmpty()) conn.output.write(payload)
                conn.output.flush()
            }
        } catch (e: IOException) {
            logger.log("write failed: ${e.message}")
            Thread { stop("write failed: ${e.message}") }.start()
        }
    }

    private fun headerValid(b: ByteArray): Boolean {
        val bb = ByteBuffer.wrap(b, 0, 24).order(ByteOrder.LITTLE_ENDIAN)
        return bb.getInt(20) == bb.getInt(0) xor -1
    }

    private fun ascii(b: ByteArray) = String(b, Charsets.US_ASCII)

    companion object {
        private const val TAG = "AdbTransport"
        private const val VERSION = 0x01000000
        private const val VERSION_SKIP_CHECKSUM = 0x01000001
        private val EMPTY = ByteArray(0)
        private val BANNER = "device::ro.product.name=jadbd".toByteArray(Charsets.US_ASCII)

        private fun cmd(s: String) = s[0].code or (s[1].code shl 8) or (s[2].code shl 16) or (s[3].code shl 24)
        val CMD_CNXN = cmd("CNXN")
        val CMD_OPEN = cmd("OPEN")
        val CMD_OKAY = cmd("OKAY")
        val CMD_CLSE = cmd("CLSE")
        val CMD_WRTE = cmd("WRTE")
    }
}
