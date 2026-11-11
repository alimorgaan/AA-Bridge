package dev.caraalink.aa

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import dev.caraalink.settings.AppSettings
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Captures the phone's mic and streams it to [onChunk] as raw PCM, in the
 * 16kHz/16-bit/mono format advertised in [AaServiceDiscovery]'s mic service.
 * Matches the chunking cadence of openauto's
 * `MediaSourceService::onMediaSourceDataReady` loop (read -> send -> read).
 *
 * Routing follows Settings > Android Auto > "Mic / speaker": with
 * `BT_PREFERRED` or `PHONE_DIRECT` (default), [AudioSource.VOICE_RECOGNITION] follows
 * whatever `AudioManager` currently treats as the active communication
 * device, which is the connected BT headset/car kit's SCO mic when one is
 * present (same as any other app's call audio) and the on-device mic
 * otherwise. `DEVICE_ONLY` explicitly turns Bluetooth SCO off first so a
 * paired BT device can't be picked even if connected.
 */
class AaMicCapture(private val context: Context, private val onChunk: (ByteArray) -> Unit) {

    companion object {
        private const val TAG = "AaMicCapture"
        private const val SAMPLE_RATE = 16_000
        // 20ms per chunk at 16kHz/16-bit/mono.
        private const val CHUNK_BYTES = (SAMPLE_RATE / 50) * 2
    }

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private var record: AudioRecord? = null

    @SuppressLint("MissingPermission") // caller is responsible for RECORD_AUDIO having been granted
    fun start() {
        if (!running.compareAndSet(false, true)) return
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (AppSettings.aaAudioRoute(context) == AppSettings.AaAudioRoute.DEVICE_ONLY) {
            runCatching { audioManager?.stopBluetoothSco() }
            runCatching { audioManager?.isBluetoothScoOn = false }
        }
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) {
            Log.w(TAG, "getMinBufferSize failed: $minBuf")
            running.set(false)
            return
        }
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuf.coerceAtLeast(CHUNK_BYTES * 4),
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "AudioRecord init failed")
            rec.release()
            running.set(false)
            return
        }
        record = rec
        rec.startRecording()
        thread = Thread({
            val buf = ByteArray(CHUNK_BYTES)
            while (running.get()) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) onChunk(if (n == buf.size) buf.copyOf() else buf.copyOf(n))
            }
        }, "AaMicCapture").apply { isDaemon = true; start() }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        thread?.interrupt()
        thread = null
        runCatching { record?.stop() }
        runCatching { record?.release() }
        record = null
    }
}
