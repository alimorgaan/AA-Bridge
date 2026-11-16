package dev.caraalink.link

import android.view.MotionEvent
import dev.caraalink.diagnostics.DiagnosticLogger
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Head unit's CONFIG_CAPTURE request (layout in claude/roadmap.md). */
data class CaptureConfig(
    val width: Int,
    val height: Int,
    val wantFps: Int,
    val wantEncoder: Int,
    val bitRate: Int,
    val orientation: Int,
    val displayId: Int,
    val supportExtendProtocol: Int,
    /** CarbitLink sizes go down to a multiple of 16. QDLink only rounds to even. */
    val align: Int = 16,
) {
    val encodeWidth get() = width and (align - 1).inv()
    val encodeHeight get() = height and (align - 1).inv()

    companion object {
        fun parse(b: ByteArray): CaptureConfig {
            val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
            val w = bb.short.toInt() and 0xFFFF
            val h = bb.short.toInt() and 0xFFFF
            val fps = bb.int
            val enc = bb.int.let { if (it == 0) 1 else it }
            bb.int // supportCodec
            bb.short; bb.short // min/max quality
            val bitRate = bb.int
            var orientation = 0
            var displayId = 0
            var ext = 0
            if (bb.remaining() >= 8) {
                bb.get(); bb.get() // capScreenMode, touchMode
                orientation = bb.get().toInt()
                displayId = bb.get().toInt()
                bb.get() // videoType
                ext = bb.get().toInt()
            }
            return CaptureConfig(w, h, fps, enc, bitRate, orientation, displayId, ext)
        }
    }
}

/**
 * What a mirror channel needs from an ADB stream -- just enough to be
 * implemented by a fake in a pure-JVM test, instead of requiring a real
 * [AdbTransport] (and the USB accessory under it). [AdbTransport.Stream] is
 * the real, production implementation; `FakeStream` in the test sources is
 * the headless one (see MirrorChannelsTest.kt).
 */
interface MirrorStream {
    val localId: Int
    val closed: Boolean
    fun write(data: ByteArray)
    fun backlogBytes(): Int
    fun clearBacklog()
}

/** Callbacks from the mirror channels into [CarSession]. */
interface MirrorHost {
    fun onCaptureConfig(config: CaptureConfig)
    fun onStartVideo(): Boolean
    /** One finger's DOWN/MOVE/UP; [slot] tells the car's fingers apart. */
    fun onTouch(action: Int, x: Int, y: Int, slot: Int)
    fun onKey(key: Int)
    fun onDataStreamOpen(stream: MirrorStream)
    fun onDataStreamClosed(stream: MirrorStream)
    fun onFrameRequested(stream: MirrorStream)
}

private fun req(type: Int, body: ByteArray?): ByteArray {
    val len = body?.size ?: 0
    return ByteBuffer.allocate(8 + len).order(ByteOrder.LITTLE_ENDIAN)
        .putShort(type.toShort()).putShort(len.toShort()).putInt(0)
        .apply { if (body != null) put(body) }.array()
}

/** Splits a byte stream into 8-byte-head (`short type, short len, int token`) messages. */
private class ReqFramer {
    private var buf = ByteArray(0)
    fun feed(data: ByteArray, onMessage: (type: Int, body: ByteArray) -> Unit) {
        buf += data
        while (buf.size >= 8) {
            val bb = ByteBuffer.wrap(buf, 0, 8).order(ByteOrder.LITTLE_ENDIAN)
            val type = bb.getShort(0).toInt() and 0xFFFF
            val len = bb.getShort(2).toInt() and 0xFFFF
            if (buf.size < 8 + len) return
            val body = buf.copyOfRange(8, 8 + len)
            buf = buf.copyOfRange(8 + len, buf.size)
            onMessage(type, body)
        }
    }
}

/** Mirror control channel, tcp:10921. */
class MirrorControlChannel(
    private val logger: DiagnosticLogger,
    private val host: MirrorHost,
    private val context: android.content.Context? = null,
) : AdbTransport.StreamHandler {

    private val framers = HashMap<Int, ReqFramer>()

    override fun onOpen(stream: MirrorStream) {
        framers[stream.localId] = ReqFramer()
    }

    override fun onClose(stream: MirrorStream) {
        framers.remove(stream.localId)
    }

    override fun onData(stream: MirrorStream, data: ByteArray) {
        val framer = framers.getOrPut(stream.localId) { ReqFramer() }
        framer.feed(data) { type, body -> handle(stream, type, body) }
    }

    private fun handle(stream: MirrorStream, type: Int, body: ByteArray) {
        // Protocol sniffer (Settings > Debug > "Verbose protocol logging"):
        // normally heartbeat/screen-event are too frequent to log, but when
        // the sniffer is on, log every message with a hex preview.
        val sniffing = context != null && dev.caraalink.settings.AppSettings.verboseLogging(context)
        if (type != REQ_SCREEN_EVENT && type != REQ_HEARTBEAT) {
            logger.log("MIRROR ctrl 0x%04X len=%d".format(type, body.size) + if (sniffing) " " + dev.caraalink.diagnostics.ProtocolSniffer.hexPreview(body) else "")
        } else if (sniffing) {
            logger.log("MIRROR ctrl 0x%04X len=%d [sniff] ".format(type, body.size) + dev.caraalink.diagnostics.ProtocolSniffer.hexPreview(body))
        }
        when (type) {
            REQ_GET_VERSION -> stream.write(req(0x31, ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(5002).putInt(1).array()))
            REQ_HEARTBEAT -> stream.write(req(0x41, null))
            REQ_CONFIG_CAPTURE -> {
                val cfg = CaptureConfig.parse(body)
                logger.log("CONFIG_CAPTURE $cfg")
                host.onCaptureConfig(cfg)
                val rly = ByteBuffer.allocate(if (cfg.supportExtendProtocol != 0) 9 else 8).order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(H264).putShort(cfg.encodeWidth.toShort()).putShort(cfg.encodeHeight.toShort())
                if (cfg.supportExtendProtocol != 0) rly.put(cfg.supportExtendProtocol.toByte())
                stream.write(req(0x11, rly.array()))
            }
            REQ_DATA_START -> stream.write(req(if (host.onStartVideo()) 0x71 else RLY_INIT_FAILED, null))
            REQ_SCREEN_EVENT -> {
                if (body.size < 10) return
                val bb = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
                val evType = bb.short.toInt()
                val x = bb.short.toInt()
                val y = bb.short.toInt()
                val slot = bb.short.toInt() and 0xFFFF
                val key = bb.short.toInt() and 0xFFFF
                when (evType) {
                    1 -> host.onTouch(MotionEvent.ACTION_UP, x, y, slot)
                    2 -> host.onTouch(MotionEvent.ACTION_DOWN, x, y, slot)
                    3 -> host.onTouch(MotionEvent.ACTION_MOVE, x, y, slot)
                    4 -> host.onKey(key)
                }
            }
            else -> stream.write(req(RLY_UN_SUPPORT, null))
        }
    }

    companion object {
        const val REQ_CONFIG_CAPTURE = 0x10
        const val REQ_SCREEN_EVENT = 0x20
        const val REQ_GET_VERSION = 0x30
        const val REQ_HEARTBEAT = 0x40
        const val REQ_DATA_START = 0x70
        const val RLY_UN_SUPPORT = 0x7F02
        const val RLY_INIT_FAILED = 0x7F05
        const val H264 = 2
        const val KEY_HOME = 1042
        const val KEY_BACK = 1044
    }
}

/** Mirror data channel, tcp:10920: the car pulls (0x72) or we push H.264 frames. */
class MirrorDataChannel(
    private val logger: DiagnosticLogger,
    private val host: MirrorHost,
) : AdbTransport.StreamHandler {

    private val framers = HashMap<Int, ReqFramer>()

    override fun onOpen(stream: MirrorStream) {
        framers[stream.localId] = ReqFramer()
        logger.log("MIRROR data stream open (local ${stream.localId})")
        host.onDataStreamOpen(stream)
    }

    override fun onClose(stream: MirrorStream) {
        framers.remove(stream.localId)
        host.onDataStreamClosed(stream)
    }

    override fun onData(stream: MirrorStream, data: ByteArray) {
        framers.getOrPut(stream.localId) { ReqFramer() }.feed(data) { type, _ ->
            if (type == REQ_DATA_NEXT) host.onFrameRequested(stream) else stream.write(req(0x7F02, null))
        }
    }

    companion object {
        const val REQ_DATA_NEXT = 0x72

        /** One frame on the wire: int32 LE length + Annex-B bytes (non-movie mode). */
        fun frame(data: ByteArray): ByteArray =
            ByteBuffer.allocate(4 + data.size).order(ByteOrder.LITTLE_ENDIAN).putInt(data.size).put(data).array()

        val NO_FRAME = ByteArray(4)
    }
}
