package dev.caraalink.video

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface

/**
 * Surface-input H.264 encoder. Whatever is drawn to [inputSurface] (our car
 * home screen or Android Auto) comes out as Annex-B NAL units,
 * which is what the head unit's mirror data channel carries.
 */
class CarVideoEncoder(
    override val width: Int,
    override val height: Int,
    private val fps: Int,
    private val bitRate: Int,
    private val onFrame: (data: ByteArray, isConfig: Boolean, isKey: Boolean) -> Unit,
) : VideoEncoder {
    private val thread = HandlerThread("CarVideoEncoder").also { it.start() }
    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    override lateinit var inputSurface: Surface
        private set
    @Volatile private var released = false

    override fun start() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            // Static screens produce no new frames; repeat so the car keeps getting video.
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1_000_000L / fps)
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            setInteger(MediaFormat.KEY_LEVEL, levelFor(width, height, fps))
            setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            // KEY_LOW_LATENCY alone left the logged output format at
            // latency=4 (frames, ~133ms @ 30fps) -- longer than the 80ms
            // starvation window. KEY_LATENCY is the explicit encoder frame-
            // latency hint (separate from LOW_LATENCY); added per
            // OpenCFLink's own encoder config. Not guaranteed honored by
            // this hardware -- check the logged output format for whether
            // latency actually drops after this.
            setInteger(MediaFormat.KEY_LATENCY, 1)
        }
        val callback = object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {}

            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                if (released) return
                try {
                    val buf = codec.getOutputBuffer(index)
                    if (buf != null && info.size > 0) {
                        val bytes = ByteArray(info.size)
                        buf.position(info.offset)
                        buf.get(bytes)
                        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        val isKey = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                        onFrame(bytes, isConfig, isKey)
                    }
                    codec.releaseOutputBuffer(index, false)
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "output after release", e)
                }
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                Log.e(TAG, "encoder error", e)
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                Log.i(TAG, "format: $format")
            }
        }
        val handler = Handler(thread.looper)
        codec.setCallback(callback, handler)
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            // Some encoders reject the baseline/level/low-latency hints; retry plain.
            Log.w(TAG, "configure with hints failed, retrying plain", e)
            listOf(MediaFormat.KEY_PROFILE, MediaFormat.KEY_LEVEL, MediaFormat.KEY_LOW_LATENCY, MediaFormat.KEY_LATENCY).forEach { format.removeKey(it) }
            codec.reset()
            codec.setCallback(callback, handler)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        inputSurface = codec.createInputSurface()
        codec.start()
    }

    override fun requestKeyFrame() {
        if (released) return
        runCatching { codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
    }

    override fun release() {
        if (released) return
        released = true
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { inputSurface.release() }
        thread.quitSafely()
    }

    companion object {
        private const val TAG = "CarVideoEncoder"

        /**
         * Confirmed from a real log (chatgpt-status.md "Level mismatch"
         * finding): CarbitLink's own CONFIG_CAPTURE asked for 1920x720 for
         * the Mirror channel -- 5400 macroblocks (1920/16 * 720/16). This
         * class was hardcoding AVCLevel31, whose H.264-spec MaxFS is 3600
         * macroblocks: every Mirror frame was ~50% over the level we told
         * the encoder we were. That mismatch, not anything content-specific,
         * is almost certainly the "blocky even on the home screen, even
         * with nothing moving" artifacting -- an encoder told it's a lower
         * level than the picture it's actually being fed doesn't reliably
         * reject that input, it can just produce corrupted macroblocks.
         * This picks the lowest standard level whose spec MaxFS/MaxMBPS
         * actually covers the resolution CarbitLink asked for, instead of
         * a single hardcoded level that happened to fit AA's 1280x720 but
         * not Mirror's 1920x720. Table values are the official H.264 Annex
         * A limits (ITU-T H.264, Table A-1), width/height already rounded
         * to the macroblock grid by [CaptureConfig.encodeWidth/Height].
         */
        internal fun levelFor(width: Int, height: Int, fps: Int): Int {
            val mbWidth = (width + 15) / 16
            val mbHeight = (height + 15) / 16
            val frameSize = mbWidth * mbHeight
            val mbps = frameSize * fps
            // level to (MaxMBPS, MaxFS), ascending.
            val table = listOf(
                MediaCodecInfo.CodecProfileLevel.AVCLevel1 to (1485 to 99),
                MediaCodecInfo.CodecProfileLevel.AVCLevel11 to (3000 to 396),
                MediaCodecInfo.CodecProfileLevel.AVCLevel12 to (6000 to 396),
                MediaCodecInfo.CodecProfileLevel.AVCLevel13 to (11880 to 396),
                MediaCodecInfo.CodecProfileLevel.AVCLevel2 to (11880 to 396),
                MediaCodecInfo.CodecProfileLevel.AVCLevel21 to (19800 to 792),
                MediaCodecInfo.CodecProfileLevel.AVCLevel22 to (20250 to 1620),
                MediaCodecInfo.CodecProfileLevel.AVCLevel3 to (40500 to 1620),
                MediaCodecInfo.CodecProfileLevel.AVCLevel31 to (108000 to 3600),
                MediaCodecInfo.CodecProfileLevel.AVCLevel32 to (216000 to 5120),
                MediaCodecInfo.CodecProfileLevel.AVCLevel4 to (245760 to 8192),
                MediaCodecInfo.CodecProfileLevel.AVCLevel41 to (245760 to 8192),
                MediaCodecInfo.CodecProfileLevel.AVCLevel42 to (522240 to 8704),
                MediaCodecInfo.CodecProfileLevel.AVCLevel5 to (589824 to 22080),
            )
            val fit = table.firstOrNull { (_, limits) ->
                val (maxMbps, maxFs) = limits
                frameSize <= maxFs && mbps <= maxMbps
            }
            if (fit == null) {
                Log.w(TAG, "levelFor: ${width}x$height@${fps}fps exceeds every standard level's table entry -- using the highest one anyway")
            }
            return (fit ?: table.last()).first
        }
    }
}
