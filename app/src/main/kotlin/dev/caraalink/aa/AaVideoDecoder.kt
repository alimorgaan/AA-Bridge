package dev.caraalink.aa

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Minimal H.264 Annex-B decoder for the AA video channel's raw NAL bytes,
 * rendering straight to a [Surface]. Standard [MediaCodec] usage (not part
 * of any reverse-engineered protocol) -- each [feed] call is one AA
 * `MEDIA_MESSAGE_DATA` frame's payload after the 8-byte timestamp is
 * stripped (see [AaSessionProbe.videoSink]). A frame containing inline
 * SPS/PPS + IDR NALs (AA's first frame) initializes the decoder without
 * needing CSD buffers, same as any Annex-B keyframe.
 */
class AaVideoDecoder(private val width: Int, private val height: Int) {

    private var codec: MediaCodec? = null
    private var thread: HandlerThread? = null
    private var freeInputs = LinkedBlockingQueue<Int>()

    @Synchronized
    fun start(surface: Surface) {
        stop()
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            c.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                .isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
        ) {
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }
        val inputs = LinkedBlockingQueue<Int>()
        val t = HandlerThread("AaVideoDecoder").also { it.start() }
        // Async mode shows each frame as soon as it's decoded, instead of when the next frame is fed in.
        c.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                inputs.offer(index)
            }

            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                runCatching { codec.releaseOutputBuffer(index, true) }
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                Log.w(TAG, "decoder error", e)
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {}
        }, Handler(t.looper))
        c.configure(format, surface, null, 0)
        c.start()
        codec = c
        thread = t
        freeInputs = inputs
    }

    @Synchronized
    fun feed(timestampUs: Long, nal: ByteArray) {
        val c = codec ?: return
        dev.caraalink.diagnostics.PerfStats.recordAaFrame(nal.size)
        try {
            val index = freeInputs.poll(10, TimeUnit.MILLISECONDS) ?: run {
                dev.caraalink.diagnostics.PerfStats.aaDecoderMisses++
                return
            }
            val buffer = c.getInputBuffer(index) ?: return
            buffer.clear()
            buffer.put(nal)
            c.queueInputBuffer(index, 0, nal.size, timestampUs, 0)
        } catch (e: Exception) {
            Log.w(TAG, "feed failed", e)
        }
    }

    @Synchronized
    fun stop() {
        codec?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        codec = null
        thread?.quitSafely()
        thread = null
    }

    private companion object {
        const val TAG = "AaVideoDecoder"
    }
}
