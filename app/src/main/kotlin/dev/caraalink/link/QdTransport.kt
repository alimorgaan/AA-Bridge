package dev.caraalink.link

import android.content.res.Resources
import android.os.Build
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

/**
 * Reassembles car frames from 512-byte reads. Zeros between frames are padding,
 * anything else before a magic is reported as junk.
 */
class QdReader(
    private val onFrame: (msgType: Int, payloadFormat: Int, payload: ByteArray) -> Unit,
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

    private val queue = LinkedBlockingQueue<ByteArray>()
    private val backlog = AtomicInteger()
    private val closed = AtomicBoolean()
    private var writer: Thread? = null
    private var timer: Thread? = null
    @Volatile private var lastReadAt = System.currentTimeMillis()
    @Volatile private var reads = 0
    @Volatile private var whitelist = false
    private var whitelistWanted = false
    private var carW = 800
    private var carH = 480
    private var encodingType = 3
    private var fps = 0
    private var bitRate = 0
    private var interval = 0
    private var configured = false
    private var touches = 0
    @Volatile private var framesSent = 0L
    @Volatile private var videoBytes = 0L
    private val video = VideoStream()

    fun start() {
        val a = conn.accessory
        logger.log("QD accessory: manufacturer=${a.manufacturer} model=${a.model} version=${a.version} " +
            "description=${a.description} uri=${a.uri} serial=${runCatching { a.serial }.getOrNull()}")
        writer = thread(name = "QDLink-writer", isDaemon = true) { writeLoop() }
        logger.log("QD TX: AppStatus (!BIN)")
        send(QdFrames.appStatus(Build.VERSION.SDK_INT))
        thread(name = "QDLink-reader", isDaemon = true) { readLoop() }
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

    private fun send(frame: ByteArray) {
        if (closed.get()) return
        backlog.addAndGet(frame.size)
        queue.put(frame)
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
                conn.output.write(f)
                backlog.addAndGet(-f.size)
            }
        } catch (_: InterruptedException) {
        } catch (e: IOException) {
            stop("write failed: ${e.message}")
        }
    }

    private fun readLoop() {
        val buf = ByteArray(QdFrames.CHUNK)
        val reader = QdReader(::onFrame) { junk -> logger.logBytes("QD RX junk", junk, minOf(junk.size, 64)) }
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
                if (tick % 3 == 1) sendJson(QdFrames.TYPE_CMD, """{"CMD":"HEARTBEAT"}""", log = tick == 1)
                if (whitelist) sendJson(QdFrames.TYPE_APP, WHITELIST_ON, log = false)
                val silentMs = System.currentTimeMillis() - lastReadAt
                if (silentMs >= SILENCE_MS && !silent) {
                    silent = true
                    logger.log("QD: no bytes from the car for ${silentMs / 1000}s (reads=$reads)")
                } else if (silentMs < SILENCE_MS && silent) {
                    silent = false
                    logger.log("QD: the car is sending again")
                }
                if (tick % 10 == 0) logger.log("QD stats: reads=$reads videoFrames=$framesSent videoBytes=$videoBytes backlog=${backlog.get()}")
            }
        } catch (_: InterruptedException) {
        }
    }

    private fun onFrame(msgType: Int, payloadFormat: Int, payload: ByteArray) {
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
            "CAR_INFO" -> {
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

    private fun configure() {
        configured = true
        // QDLink 1.9.8's defaults when the car asks for nothing.
        val f = fps.takeIf { it > 0 } ?: 24
        val b = bitRate.takeIf { it > 0 } ?: 4_000_000
        logger.log("QD video: ${carW}x$carH fps=$f bitRate=$b (car asked fps=$fps bitRate=$bitRate interval=$interval type=$encodingType)")
        host.onCaptureConfig(CaptureConfig(carW, carH, f, 1, b, 0, 0, 0, align = 2))
    }

    private fun phoneInfo(w: Int, h: Int, mirrorType: Int): JSONObject {
        val dm = Resources.getSystem().displayMetrics
        val long = maxOf(dm.widthPixels, dm.heightPixels)
        val short = minOf(dm.widthPixels, dm.heightPixels)
        val phoneAspect = long.toFloat() / short
        val (mw, mh) = if (carW.toFloat() / carH > phoneAspect) even((carH * phoneAspect).toInt()) to carH
            else carW to even((carW / phoneAspect).toInt())
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
        // Byte 5 is the finger's id; with one finger down, TouchTracker accepts any id.
        host.onTouch(ev, x.toInt(), y.toInt(), p[5].toInt() and 0xFF)
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
                if (QdFrames.nalType(au) == 7) {
                    parameterSets = au
                    continue
                }
                // QDLink 1.9.8 sends SPS+PPS ahead of every frame over USB.
                parameterSets?.let { send(videoFrame(it)) }
                send(videoFrame(au))
                framesSent++
                videoBytes += n
                if (framesSent <= 3) logger.log("QD video frame #$framesSent ($n bytes, nal ${QdFrames.nalType(au)})")
            }
        }

        private fun videoFrame(au: ByteArray) = QdFrames.frame(QdFrames.TYPE_VIDEO, QdFrames.FORMAT_H264, au,
            QdFrames.videoExt(carW, carH, encodingType, fps, bitRate, interval))

        override fun backlogBytes() = backlog.get()

        override fun clearBacklog() {
            queue.clear()
            backlog.set(0)
        }
    }

    companion object {
        const val MANUFACTURER = "Neusoft"
        private const val QDLINK_VERSION = "1.9.8"
        private const val RAW_READS_LOGGED = 12
        private const val TOUCHES_LOGGED = 200
        private const val SILENCE_MS = 5_000L
        private const val WHITELIST_ON = """{"AppID":"Mirror","FunctionID":"WhitelistAppOn","Para":{"WhitelistAppOn":1}}"""

        private fun even(v: Int) = if (v % 2 != 0) v + 1 else v
    }
}
