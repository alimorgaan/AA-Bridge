package dev.caraalink.aa

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import dev.caraalink.settings.AppSettings
import java.nio.ByteBuffer

/**
 * Plays the AA media-sink audio channel's AAC-LC stream out the phone's own
 * speaker (or whatever `AudioManager` currently routes to -- same device
 * Android would pick for any other app's audio). Without this the channel
 * negotiates and streams fine but nothing is ever done with the bytes, which
 * is why AA audio was silent regardless of Bluetooth routing: playback was
 * simply never implemented, not a routing bug.
 *
 * AAC-LC needs its AudioSpecificConfig (the codec-config bytes, 2 bytes for
 * the plain LC case) as `csd-0` before the first real frame -- standard
 * MediaCodec usage, not part of the AA protocol itself.
 */
class AaAudioPlayer(
    private val context: Context,
    private val sampleRate: Int,
    private val channelCount: Int,
    /** True only for the on-phone Car Preview session -- see AaServiceDiscovery.configFromSettings(). */
    private val preview: Boolean = false,
) {

    companion object {
        private const val TAG = "AaAudioPlayer"
    }

    private var codec: MediaCodec? = null
    private var track: AudioTrack? = null
    private var trackStarted = false
    private val bufferInfo = MediaCodec.BufferInfo()
    private var lastCsd0: ByteArray? = null
    private val logger = dev.caraalink.diagnostics.DiagnosticLogger(context, "aa-audio")

    /**
     * No-ops if [csd0] matches what's already configured and a decoder is
     * already running. AA can resend codec config defensively (e.g. a short
     * voice-prompt stream starting while music is already playing) even
     * when nothing actually changed; tearing down and recreating the
     * decoder/track every time that happens was killing whatever was
     * already mid-playback and never resuming it.
     */
    @Synchronized
    fun setCodecConfig(csd0: ByteArray) {
        if (codec != null && track != null && csd0.contentEquals(lastCsd0)) return
        lastCsd0 = csd0
        stop()
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(csd0))
        }
        val c = runCatching {
            MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(format, null, null, 0)
                start()
            }
        }.onFailure {
            Log.w(TAG, "decoder init failed", it)
            logger.log("AAC decoder init failed: $it")
        }.getOrNull() ?: return

        val channelMask = if (channelCount >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .build(),
            )
            .setBufferSizeInBytes(minBuf.coerceAtLeast(4096) * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        // CarbitLink's own wire protocol doesn't carry audio to the car at
        // all (its Packet framing for audio is still unconfirmed -- see
        // claude/roadmap.md item 6 -- so nothing here invents bytes for it).
        // The practical way AA audio actually reaches the car's speakers
        // today is the car's own Bluetooth, which almost every head unit
        // already has paired for calls/music. Explicitly steering output to
        // a connected A2DP device (instead of hoping default routing picks
        // it over the earpiece/speaker) is what "Mic / speaker for Android
        // Auto" = BT preferred now also controls for playback, not just mic.
        if (preview) {
            // Car Preview is always played on THIS phone, regardless of the
            // real car's "Mic / speaker for Android Auto" setting -- that
            // setting is about the real car's Bluetooth, meaningless here,
            // and respecting it in preview was a confirmed real regression:
            // whatever the owner last had it set to for real-car testing
            // silently carried over and routed preview audio wrong too.
            // Always force the phone's own speaker in preview instead.
            val am = context.getSystemService(AudioManager::class.java)
            val speaker = am?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                ?.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            if (speaker != null) {
                runCatching { t.preferredDevice = speaker }
                logger.log("AA audio (preview) -> built-in speaker (forced)")
            } else {
                logger.log("AA audio (preview) -> default routing (no built-in speaker device found)")
            }
        } else {
            // Unconstrained ("default") routing is the risky case: this app
            // always has an active USB accessory connection to the dongle
            // for the whole session, and PCM has repeatedly shown up as
            // genuinely written to AudioTrack (real frames-delivered counts)
            // while still reported inaudible -- consistent with audio
            // silently going out over that USB connection instead of
            // anywhere audible. Always pick an explicit device: A2DP if
            // BT-preferred and actually connected, else the phone's own
            // speaker as a safe, always-audible fallback (confirmed live:
            // "BT-preferred but no A2DP device" was hitting unconstrained
            // routing and going silent, even with the car paired over BT).
            val am = context.getSystemService(AudioManager::class.java)
            val devices = am?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val a2dp = if (AppSettings.aaAudioRoute(context) == AppSettings.AaAudioRoute.BT_PREFERRED) {
                devices?.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
            } else null
            when {
                a2dp != null -> {
                    runCatching { t.preferredDevice = a2dp }
                    logger.log("AA audio -> Bluetooth A2DP (${a2dp.productName})")
                }
                else -> {
                    val speaker = devices?.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    if (speaker != null) {
                        runCatching { t.preferredDevice = speaker }
                        logger.log("AA audio -> built-in speaker (no A2DP device found/wanted)")
                    } else {
                        logger.log("AA audio -> default routing (no built-in speaker device found)")
                    }
                }
            }
        }
        // PCM has repeatedly shown up as genuinely written to AudioTrack
        // (confirmed via its own "frames delivered" log on stop()) while
        // still being reported inaudible -- logging the actual media stream
        // volume here rules in/out "phone was just muted/turned down" as the
        // cause on the next test, instead of guessing again.
        runCatching {
            val am = context.getSystemService(AudioManager::class.java)
            val vol = am?.getStreamVolume(AudioManager.STREAM_MUSIC)
            val max = am?.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            logger.log("AA audio: STREAM_MUSIC volume = $vol/$max")
        }
        // NOT t.play() here -- see feed(). Starting it now, before any real
        // PCM exists to write, was confirmed from a real exported log: the
        // track sat started-but-empty and the system kept cycling it
        // (logcat spammed "disabled due to previous underrun, restarting"
        // every ~600ms for the whole session) whenever AA's audio channel
        // negotiated but never actually sent a data frame (e.g. nothing
        // was playing on the phone at the time) -- a continuous, pointless
        // restart loop on its own audio thread, not just silence.

        codec = c
        track = t
        trackStarted = false
    }

    @Synchronized
    fun feed(aacFrame: ByteArray) {
        val c = codec ?: return
        val t = track ?: return
        try {
            val inIndex = c.dequeueInputBuffer(10_000)
            if (inIndex >= 0) {
                val buffer = c.getInputBuffer(inIndex) ?: return
                buffer.clear()
                buffer.put(aacFrame)
                c.queueInputBuffer(inIndex, 0, aacFrame.size, 0, 0)
            }
            var outIndex = c.dequeueOutputBuffer(bufferInfo, 0)
            while (outIndex >= 0) {
                val out = c.getOutputBuffer(outIndex)
                if (out != null && bufferInfo.size > 0) {
                    val pcm = ByteArray(bufferInfo.size)
                    out.get(pcm)
                    // Lazy start: the first real PCM is the signal there's
                    // actually something to play. Starting at setCodecConfig()
                    // time instead left the track spinning empty -- see there.
                    if (!trackStarted) {
                        t.play()
                        trackStarted = true
                    }
                    t.write(pcm, 0, pcm.size)
                }
                c.releaseOutputBuffer(outIndex, false)
                outIndex = c.dequeueOutputBuffer(bufferInfo, 0)
            }
        } catch (e: Exception) {
            Log.w(TAG, "feed failed", e)
        }
    }

    @Synchronized
    fun stop() {
        codec?.let { runCatching { it.stop() }; runCatching { it.release() } }
        codec = null
        track?.let { runCatching { it.stop() }; runCatching { it.release() } }
        track = null
    }
}
