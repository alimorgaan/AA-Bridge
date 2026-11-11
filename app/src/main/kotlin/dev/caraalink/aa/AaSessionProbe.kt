package dev.caraalink.aa

import android.content.Context
import android.util.Log
import dev.caraalink.diagnostics.DiagnosticLogger
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLException

/**
 * Rungs 1-2 of the Android Auto receiver: plaintext version exchange, then the
 * TLS handshake carried *inside* PLAIN control messages.
 *
 * Rung 1 is confirmed on hardware (S25 Ultra, AA 18.0.664244):
 *
 * ```
 * TX 00 03 00 06 00 01 00 01 00 06
 * RX 00 02 00 01 00 07 00 00   -> VERSION_RESPONSE 1.7, status 0
 * ```
 *
 * Rung 2 follows `ControlServiceChannel::sendHandshake()` /
 * `receive()` in aasdk: the head unit is the TLS *client*, and each TLS
 * record is shipped as a PLAIN control message with id
 * `MESSAGE_ENCAPSULATED_SSL` (3). That is why raw TLS on the socket never
 * worked — the socket only ever carries AA frames.
 *
 * Java has no socket to hand to JSSE here, so this uses [SSLEngine], which
 * does the same job over memory buffers: wrap -> send as an encapsulated-SSL
 * message, receive -> unwrap. Nothing above TLS is interpreted.
 */
class AaSessionProbe(
    private val context: Context,
    private val config: Config = Config(),
) {

    data class Config(
        val host: String = "127.0.0.1",
        val port: Int = 5277,
        val connectTimeoutMs: Int = 5_000,
        val readTimeoutMs: Int = 15_000,
        // Discovery + audio focus + one CHANNEL_OPEN_REQUEST per registered
        // service already uses ~10 messages before any media setup happens;
        // the old default of 10 made the probe hang up on itself (closing the
        // socket in its own `finally`) right as the real session began --
        // gearhead's reader thread logged "end of stream received" at that
        // exact point, which looked like a protocol rejection but wasn't one.
        val maxMessages: Int = 2_000,
        /** Overrides the version we advertise; null uses AaFraming.PROTOCOL_MAJOR/MINOR. */
        val versionMajor: Int? = null,
        val versionMinor: Int? = null,
        // The server can refuse connections for ~20s after Start, so retry well past that.
        val maxAttempts: Int = 8,
        val retryDelayMs: Long = 2_000,
        /** True only for the on-phone Car Preview session -- see AaServiceDiscovery.configFromSettings(). */
        val preview: Boolean = false,
    )

    companion object {
        private const val TAG = "AaSessionProbe"
        private const val SHUTDOWN_ACK_TIMEOUT_MS = 1_000L
        private const val SENSOR_NIGHT_MODE = 10L

        @Volatile
        var active: AaSessionProbe? = null
            private set
    }

    private val logger = DiagnosticLogger(context, "aa-session")
    private val running = AtomicBoolean(false)
    /** Called each time the phone reaches service discovery, i.e. a session is up. */
    @Volatile
    var onEstablished: (() -> Unit)? = null
    /** Called when the initial retries ran out without a session (not after stop()). */
    @Volatile
    var onGaveUp: (() -> Unit)? = null
    private var socket: Socket? = null
    private var worker: Thread? = null

    /**
     * Called for each `MEDIA_MESSAGE_DATA` (id 0) frame on the video channel,
     * with the raw H.264 Annex-B NAL bytes (the 8-byte big-endian timestamp
     * aasdk's `Timestamp`/`handleMediaWithTimestampIndication()` prefixes
     * each frame with is already stripped). Set before [start]; null means
     * frames are just logged and discarded.
     */
    @Volatile
    var videoSink: ((timestampUs: Long, nal: ByteArray) -> Unit)? = null

    // The phone sends SPS/PPS exactly once per session (MEDIA_MESSAGE_CODEC_CONFIG,
    // right after setup), not on every attach. A fresh MediaCodec decoder needs
    // it to initialize -- without this, closing and reopening AaPreviewActivity
    // while the same session is still running attaches a brand-new decoder that
    // never gets parameter sets and just stays black. Cache it and replay it to
    // every new sink.
    @Volatile
    private var cachedCodecConfig: ByteArray? = null

    // SPS/PPS alone isn't enough to produce a picture: a brand-new decoder
    // still needs an actual IDR (keyframe) frame to decode from, and the
    // stream's own next keyframe can be many seconds away (H.264 GOP
    // interval, not something we control). Confirmed live: codec config was
    // being replayed correctly on reattach, a new MediaCodec did
    // initialize, DATA frames kept arriving the whole time, and the
    // preview still stayed black -- because every frame arriving in that
    // window was a P-frame referencing a decoder state the new instance
    // never had. Caching the last IDR and replaying it right after the
    // codec config gives a fresh attach a self-contained starting point
    // instead of waiting on the stream's own schedule.
    @Volatile
    private var cachedKeyframe: ByteArray? = null

    /** Wall-clock time of the last video DATA frame actually received -- the watchdog's stall signal. */
    @Volatile
    var lastVideoFrameAt: Long = 0L
        private set

    /** True only while a session is up, not during reconnect waits. */
    @Volatile
    var isConnectionActive: Boolean = false
        private set

    /** Set on the first real video frame; separates "still starting" from "stalled". */
    @Volatile
    var hasReceivedVideo: Boolean = false
        private set

    /** Like setting [videoSink] directly, but also replays cached SPS/PPS + last keyframe if we have them. */
    fun attachVideoSink(sink: (timestampUs: Long, nal: ByteArray) -> Unit) {
        videoSink = sink
        cachedCodecConfig?.let { sink(0L, it) }
        cachedKeyframe?.let { sink(0L, it) }
    }

    /** True if [nal] (one or more Annex-B NAL units) contains an IDR slice (type 5). */
    private fun containsIdr(nal: ByteArray): Boolean {
        var i = 0
        while (i < nal.size - 4) {
            if (nal[i] == 0.toByte() && nal[i + 1] == 0.toByte() &&
                (nal[i + 2] == 1.toByte() || (nal[i + 2] == 0.toByte() && i + 3 < nal.size && nal[i + 3] == 1.toByte()))
            ) {
                val headerAt = if (nal[i + 2] == 1.toByte()) i + 3 else i + 4
                if (headerAt < nal.size && (nal[headerAt].toInt() and 0x1F) == 5) return true
                i = headerAt
            } else {
                i++
            }
        }
        return false
    }

    @Volatile
    private var activeChannel: AaTlsChannel? = null

    // Set by stop() before `running` clears, so the read loop keeps going until the phone answers the ShutdownRequest.
    @Volatile
    private var draining = false

    // Set from service discovery; when true the audio channel only carries system sounds.
    private var phoneMediaAudio = false
    private val shutdownAcked = java.util.concurrent.CountDownLatch(1)

    @Volatile
    private var micCapture: AaMicCapture? = null

    @Volatile
    private var audioPlayer: AaAudioPlayer? = null

    // AVMediaAckIndication (0x8004) flow control, confirmed from
    // mrmees/open-android-auto's protocol docs (GPL-3.0, paraphrased facts
    // only -- field numbers/names, not their prose): every MEDIA_SETUP
    // response here grants max_unacked=1, but nothing ever sent this ack
    // back -- the phone tracks unacked AV frames against that limit and
    // simply stops sending more once it's hit, with no timeout recovery.
    // That's exactly the observed symptom: one audio MEDIA_CODEC_CONFIG,
    // then silence forever. `session` comes from each channel's own
    // MEDIA_START (AVChannelStartIndication) and must be echoed back.
    private val avAckSession = java.util.concurrent.ConcurrentHashMap<Int, Int>()

    private fun sendAvAck(channel: AaTlsChannel, channelId: Int) {
        val session = avAckSession[channelId] ?: 0
        // Field 2 is how many frames this ack covers, not a running total. AA drops the session above 4096.
        val ack = AaProtobuf.Writer().int(1, session).int(2, 1).build()
        runCatching { channel.sendChannelSpecific(channelId, AaFraming.MSG_AV_MEDIA_ACK, ack) }
            .onFailure { logger.log("AV_MEDIA_ACK send failed on ch$channelId: $it") }
    }

    // sendTouch() is called from whatever thread owns the preview UI (not
    // this session's own worker thread) -- a raw socket write there would
    // throw NetworkOnMainThreadException on the UI thread. One background
    // thread serializes all touch sends; AaTlsChannel's send/wrap/unwrap are
    // themselves @Synchronized so this doesn't race the worker thread's reads.
    private val touchExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "AaSessionProbe-touch").apply { isDaemon = true } }

    // Video/audio decode used to run inline on this probe's single message-
    // receive thread (the same thread doing TLS unwrap and replying to every
    // other control message) -- any decode/render stall there delayed ping
    // replies and every other channel too, which is a real, confirmed-live
    // "choppy video + delayed touch feedback" cause: the backlog isn't on
    // the touch SEND path (already its own thread), it's that frames render
    // behind real-time, so the screen's response to a touch lags along with
    // the video itself. A dedicated thread keeps the protocol loop free to
    // keep up with pings/control traffic regardless of how decode is doing.
    private val mediaExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "AaSessionProbe-media").apply { isDaemon = true } }

    // Confirmed live: video "struggling" (heavy frames, thermal throttling)
    // and choppy audio were reported happening at the same time -- audio
    // feed() calls were queued on the SAME single mediaExecutor thread as
    // video decode, so a video backlog delayed audio right along with it.
    // A separate thread means one falling behind can't stall the other.
    private val audioExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "AaSessionProbe-audio").apply { isDaemon = true } }

    /**
     * Sends one touch event on the INPUT_SOURCE channel: `InputReport{
     * timestamp=1, touch_event=3 {touch_location=1 (repeated) [{x,y,
     * pointer_id}], action_index=2, action=3}}`, `INPUT_MESSAGE_INPUT_REPORT`
     * (32769), ENCRYPTED/SPECIFIC -- cited from aasdk's
     * `InputSourceService::sendInputReport()`, field numbers/repetition
     * cross-checked against mrmees/open-android-auto's
     * `docs/protocol-reference.md` (TouchEvent `wcj` / TouchLocation `wci`,
     * GPL-3.0, paraphrased facts only). No-op if no channel is established
     * (e.g. still mid-handshake, or no session running).
     *
     * [pointers] lists every currently-down finger (x, y, pointerId) -- for
     * a single-finger tap/drag that's one entry; for pinch/rotate it's two.
     * [actionIndex] is which entry in [pointers] triggered this particular
     * event (which finger went down/up/moved); [action] is a `PointerAction`:
     * DOWN=0, UP=1, MOVED=2.
     */
    fun sendTouch(pointers: List<Triple<Int, Int, Int>>, actionIndex: Int, action: Int) {
        val channel = activeChannel ?: return
        if (pointers.isEmpty()) return
        touchExecutor.execute {
            val touchEventWriter = AaProtobuf.Writer()
            for ((x, y, pointerId) in pointers) {
                val pointer = AaProtobuf.Writer().int(1, x).int(2, y).int(3, pointerId).build()
                touchEventWriter.message(1, pointer)
            }
            val touchEvent = touchEventWriter
                .int(2, actionIndex)
                .int(3, action)
                .build()
            val report = AaProtobuf.Writer()
                .varint(1, System.nanoTime() / 1000) // timestamp (us)
                .message(3, touchEvent)
                .build()
            runCatching {
                channel.sendChannelSpecific(AaServiceDiscovery.CHANNEL_INPUT_SOURCE, AaFraming.MSG_INPUT_REPORT, report)
            }.onFailure { Log.w(TAG, "sendTouch failed", it) }
        }
    }

    /** Single-finger convenience overload -- same as `sendTouch(listOf(Triple(x,y,pointerId)), 0, action)`. */
    fun sendTouch(x: Int, y: Int, action: Int, pointerId: Int = 0) {
        sendTouch(listOf(Triple(x, y, pointerId)), 0, action)
    }

    fun start() {
        if (!running.compareAndSet(false, true)) {
            Log.w(TAG, "start: already running")
            return
        }
        logger.log("=== AA session probe ${config.host}:${config.port}")
        Log.i(TAG, "start")
        // CarSession's watchdog starts polling this within milliseconds of
        // start() returning, but lastVideoFrameAt otherwise stays at its 0L
        // default until deep into attemptOnce() (after the TLS handshake).
        // Its first tick then reads `now - 0` -- the current epoch time,
        // always "stale" -- and force-restarts a session that was simply
        // still negotiating. Confirmed from a real log: a restart fired
        // exactly on the watchdog's first 5s tick, logging a "stale" value
        // in the trillions of ms. Seeding this at start() closes that gap;
        // the later resets (post-handshake, per real frame) still apply.
        lastVideoFrameAt = System.currentTimeMillis()
        worker = Thread({ run() }, "AaSessionProbe").apply { isDaemon = true }
        active = this
        worker!!.start()
    }

    fun stop() {
        val ch = activeChannel
        draining = ch != null
        if (!running.compareAndSet(true, false)) {
            draining = false
            return
        }
        logger.log("=== session probe stopped")
        if (active === this) active = null
        // Confirmed from mrmees/open-android-auto's own protocol docs
        // (docs/interactions/05-session-maintenance-teardown.md, GPL-3.0,
        // facts only): a session end is supposed to be announced with
        // ShutdownRequest BEFORE the transport closes, so the other side
        // can clean up its own session state -- we previously just
        // abruptly closed the raw socket every time WE decided to end a
        // session (settings change, watchdog restart, leaving AA), with
        // no graceful signal at all. Plausible real-world consequence,
        // caught live via gearhead's own logcat: gearhead's developer
        // head-unit networking component crashed with "Already connected"
        // on a subsequent reconnect attempt -- consistent with its session
        // tracking never having been told the previous one actually ended
        // on purpose, only ever seeing the pipe die. Best-effort: this
        // must not block or throw if the channel's already unusable.
        // stop() is usually called on the main thread, where this socket write threw
        // NetworkOnMainThreadException, so the request never went out. Tear down on a worker.
        Thread({
            if (ch != null) {
                val sent = runCatching {
                    val reason = AaProtobuf.Writer().int(1, 1).build() // ShutdownReason.USER_SELECTION
                    ch.sendControlEncrypted(AaFraming.MSG_SHUTDOWN_REQUEST, reason)
                }.onFailure { logger.log("graceful ShutdownRequest send failed (continuing teardown): $it") }.isSuccess
                if (sent && !shutdownAcked.await(SHUTDOWN_ACK_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    logger.log("no SHUTDOWN_RESPONSE within ${SHUTDOWN_ACK_TIMEOUT_MS}ms -- closing anyway")
                }
                draining = false
            }
            runCatching { socket?.close() }
            socket = null
            worker?.interrupt()
            worker = null
            // Each reconnect builds a brand-new AaSessionProbe (openAndroidAuto()),
            // each with its own executors -- without this they'd leak one idle
            // daemon thread pool per reconnect, which during the ~every-minute
            // drop/reconnect cycle this session is still seeing adds up fast.
            touchExecutor.shutdownNow()
            mediaExecutor.shutdownNow()
            audioExecutor.shutdownNow()
        }, "AaSessionProbe-stop").apply { isDaemon = true }.start()
    }

    /**
     * Set once a real session establishes (service discovery accepted). After
     * that point `run()` keeps reconnecting indefinitely rather than giving
     * up after [Config.maxAttempts] -- a dropped link (car power cycle, phone
     * screen lock, etc.) should auto-recover, not need a manual retrigger.
     * Bounded retries still apply before the *first* successful session, so a
     * genuinely absent/dead head-unit-server doesn't get hammered forever.
     */
    @Volatile
    private var everEstablished = false

    private fun run() {
        // The gearhead's head unit server appears to service one connection at
        // a time and goes deaf to the next one after a session dies uncleanly
        // (observed: attempt at 16:54:18 answered instantly, the one at
        // 16:55:17 got no reply at all). Retry on a fresh socket rather than
        // reporting a protocol failure that is really a stale-session artefact.
        var attempt = 0
        try {
            while (running.get() && (everEstablished || attempt < config.maxAttempts)) {
                attempt++
                logger.log(
                    if (everEstablished) "--- reconnecting (auto-reconnect, session was live before)"
                    else "--- attempt $attempt/${config.maxAttempts}",
                )
                attemptOnce()
                if (!running.get()) return
                if (everEstablished) attempt = 0
                // stop() interrupts this worker thread -- if that lands
                // while it's sleeping here between retries, Thread.sleep()
                // throws InterruptedException, which was uncaught anywhere
                // in run() and crashed the ENTIRE APP. Confirmed from a real
                // crash log: toggling "Start Head Unit Server" while a probe
                // was mid-retry did exactly this. A stop mid-sleep is just
                // stop() doing its job, not a real error -- treat it as one.
                try {
                    Thread.sleep(config.retryDelayMs)
                } catch (e: InterruptedException) {
                    logger.log("sleep interrupted (stop() requested) -- ending retry loop")
                    return
                }
            }
            logger.log("gave up after $attempt attempt(s)")
            if (running.get()) runCatching { onGaveUp?.invoke() }
        } finally {
            running.set(false)
            runCatching { socket?.close() }
            socket = null
            if (active === this) active = null
        }
    }

    /** @return true if a full session ran (handshake + observation) rather than failing. */
    private fun attemptOnce(): Boolean {
        return try {
            val identity = AaIdentity.load(context)
            val s = Socket().apply {
                // Nagle held our small acks and touch writes about 30 ms each.
                tcpNoDelay = true
                connect(InetSocketAddress(config.host, config.port), config.connectTimeoutMs)
                soTimeout = config.readTimeoutMs
            }
            socket = s
            val input = DataInputStream(s.getInputStream())
            val output = s.getOutputStream()
            logger.log("tcp connected ${s.localSocketAddress} -> ${s.remoteSocketAddress}")

            // --- rung 1: version exchange ---
            val request = AaFraming.versionRequest(
                major = config.versionMajor ?: AaFraming.PROTOCOL_MAJOR,
                minor = config.versionMinor ?: AaFraming.PROTOCOL_MINOR,
            )
            logger.logBytes("TX", request, request.size)
            output.write(request); output.flush()

            val response = readFrame(input)
            logFrame(1, response)
            val versionId = AaFraming.controlMessageId(response.payload)
            if (versionId != AaFraming.MSG_VERSION_RESPONSE) {
                logger.log("unexpected first reply ${AaFraming.controlMessageName(versionId ?: -1)}")
                return false
            }
            val major = AaFraming.u16(response.payload, 2)
            val minor = AaFraming.u16(response.payload, 4)
            val status = if (response.payload.size >= 8) AaFraming.u16(response.payload, 6) else -1
            logger.log("VERSION_RESPONSE $major.$minor status=$status")

            // --- rung 2: TLS inside encapsulated-SSL control messages ---
            val engine = identity.sslContextLeafOnly.createSSLEngine(config.host, config.port).apply {
                useClientMode = true
                // Reference app pins TLSv1.2 only (`enabled=[TLSv1.2]`); offering
                // 1.3 as well made our ClientHello 233B vs their 138B. Match it.
                enabledProtocols = arrayOf("TLSv1.2")
                beginHandshake()
            }
            logger.log("TLS engine: client mode, ${engine.session.protocol} max=${engine.session.packetBufferSize}")
            handshake(engine, input, output)
            if (!running.get()) return true

            val session = engine.session
            logger.log("TLS ESTABLISHED ${session.protocol} ${session.cipherSuite}")
            Log.i(TAG, "TLS established ${session.protocol} ${session.cipherSuite}")
            logPeer(engine)

            // --- rung 3: control channel is live, wait for the phone ---
            //
            // Direction corrected against aasdk: the *phone* sends
            // SERVICE_DISCOVERY_REQUEST and the head unit answers with
            // SERVICE_DISCOVERY_RESPONSE (ENCRYPTED). So there is nothing to
            // send unprompted here -- we listen and decode.
            val channel = AaTlsChannel(engine, input, output, logger)
            activeChannel = channel

            // AUTH_COMPLETE (PLAIN) immediately after the handshake.
            // openauto's AndroidAutoEntity::onHandshake() does exactly this:
            //   if (cryptor_->doHandshake()) { AuthResponse{status=STATUS_SUCCESS};
            //     controlServiceChannel_->sendAuthComplete(...); }
            // ControlServiceChannel never *handles* AUTH_COMPLETE, so it is a
            // head-unit -> phone step only, and it was the one we were missing.
            val authResponse = AaProtobuf.Writer()
                .int(1, 0) // AuthResponse.status = MessageStatus::STATUS_SUCCESS
                .build()
            logger.log("TX AUTH_COMPLETE status=0")
            channel.sendControlPlain(AaFraming.MSG_AUTH_COMPLETE, authResponse)
            lastVideoFrameAt = System.currentTimeMillis() // grace period before the watchdog can call this stale
            isConnectionActive = true

            var index = 0
            // Confirmed from mrmees/open-android-auto's own protocol docs
            // (docs/interactions/05-session-maintenance-teardown.md,
            // GPL-3.0 -- paraphrased field layout only): "Either side may
            // initiate a ping", with sample head-unit code sending periodic
            // PingRequests proactively, not just replying to the phone's
            // own. We previously only ever replied -- never initiated.
            // Plausible, doc-supported explanation for the long-standing,
            // never-fully-explained ~30s periodic AA disconnect: gearhead
            // may expect to hear from the head unit periodically to confirm
            // it's alive, same as the sample code's own timeout-detection
            // pattern implies. Not confirmed to be the actual cause --
            // worth specifically watching whether that disconnect cadence
            // changes on the next live test.
            // maxMessages=2000 was sized for the pre-streaming startup phase
            // (discovery/channel-open chatter is ~10 messages) and was never
            // meant to cap an ACTIVE streaming session -- confirmed from a
            // real log: hitting this cap mid-stream is exactly the long-
            // standing, never-fully-explained ~30s periodic AA disconnect.
            // At 30fps video plus continuous audio chunks, 2000 incoming
            // messages passes in well under a minute, so the loop was
            // cleanly exiting (no exception, no SHUTDOWN_REQUEST -- which
            // is why it never showed up as a real failure) and immediately
            // reconnecting itself on a timer nobody asked for. Once a
            // session is actually established there's no reason to cap it
            // at all; the cap still protects the pre-streaming phase from
            // hanging indefinitely on a wedged handshake.
            var lastPingSentAt = System.currentTimeMillis()
            var nightSensorStarted = false
            var nightSent: Boolean? = null
            fun sendNightIfChanged() {
                val night = NightMode.current(context) ?: return
                if (night == nightSent) return
                nightSent = night
                logger.log("TX SENSOR_EVENT night=$night")
                val batch = AaProtobuf.Writer().message(10, AaProtobuf.Writer().bool(1, night).build()).build() // night_mode
                runCatching { channel.sendChannelSpecific(AaServiceDiscovery.CHANNEL_SENSOR, AaFraming.MSG_SENSOR_EVENT, batch) }
                    .onFailure { logger.log("SENSOR_EVENT send failed: $it") }
            }
            while ((running.get() || draining) && (everEstablished || index < config.maxMessages)) {
                index++
                if (System.currentTimeMillis() - lastPingSentAt > 5_000) {
                    lastPingSentAt = System.currentTimeMillis()
                    val ping = AaProtobuf.Writer().varint(1, lastPingSentAt * 1000).int(2, 0).build()
                    runCatching { channel.sendControlEncrypted(AaFraming.MSG_PING_REQUEST, ping) }
                        .onFailure { logger.log("proactive PING_REQUEST send failed: $it") }
                    if (nightSensorStarted) sendNightIfChanged()
                }
                val message = channel.receiveMessage()
                if (message.channelId != AaFraming.CHANNEL_CONTROL) {
                    val chId = AaFraming.controlMessageId(message.payload)
                    val chBody = if (message.payload.size >= 2) message.payload.copyOfRange(2, message.payload.size) else ByteArray(0)
                    // MEDIA_DATA fires once per video/audio frame -- DiagnosticLogger
                    // does a synchronous file write per call, so logging every one of
                    // those unconditionally was quietly throttling the decode loop.
                    // Only log those when the sniffer toggle is on; everything else
                    // (open/setup/focus messages) is rare enough to always log.
                    val sniffing = dev.caraalink.settings.AppSettings.verboseLogging(context)
                    if (chId != AaFraming.MSG_MEDIA_DATA || sniffing) {
                        val hex = if (sniffing) " " + dev.caraalink.diagnostics.ProtocolSniffer.hexPreview(chBody) else ""
                        logger.log(
                            "MESSAGE on ${AaFraming.channelName(message.channelId)} " +
                                "${AaFraming.controlMessageName(chId ?: -1)} (${chBody.size}B)$hex",
                        )
                    }
                    if (chId == AaFraming.MSG_CHANNEL_OPEN_REQUEST) {
                        // ChannelOpenRequest{priority=1 sint32, service_id=2 int32}. We
                        // accept every channel the phone asks for -- aasdk's per-service
                        // handlers (e.g. SensorSourceService::sendChannelOpenResponse())
                        // always reply ENCRYPTED, MessageType::CONTROL, msg id 8,
                        // ChannelOpenResponse{status=0 (STATUS_SUCCESS)}, on the
                        // service's own channel, not CONTROL.
                        val fields = AaProtobuf.parse(chBody)
                        val priority = fields.firstOrNull { it.number == 1 }?.varint
                        val serviceId = fields.firstOrNull { it.number == 2 }?.varint
                        logger.log(
                            "RX CHANNEL_OPEN_REQUEST on ${AaFraming.channelName(message.channelId)} " +
                                "priority=$priority service_id=$serviceId",
                        )
                        val response = AaProtobuf.Writer().int(1, 0).build() // status = STATUS_SUCCESS
                        channel.sendChannelControl(message.channelId, AaFraming.MSG_CHANNEL_OPEN_RESPONSE, response)
                    } else if (chId == AaFraming.MSG_MEDIA_SETUP) {
                        // Setup{type=1}. Video/audio sinks answer with
                        // Config{status=READY(2), max_unacked,
                        // configuration_indices=[0]} on MEDIA_MESSAGE_CONFIG
                        // (32771); the microphone source quirkily reuses
                        // MEDIA_MESSAGE_SETUP (32768) as its own response id
                        // (aasdk's MediaSourceService::sendChannelSetupResponse()).
                        //
                        // max_unacked was hardcoded to 1 for EVERY channel,
                        // video included -- confirmed wrong from
                        // mrmees/open-android-auto's own docs
                        // (docs/interactions/04-channel-lifecycle.md,
                        // GPL-3.0, facts only): video's documented typical
                        // value is 10 (a sliding window, pipelined), audio's
                        // is 1 (strict lockstep). Forcing video to 1 made
                        // every single frame wait a full ack round-trip
                        // before the phone would send the next one, instead
                        // of streaming several ahead -- a real, self-
                        // inflicted throughput cap, not just decode speed or
                        // thermal throttling.
                        // Audio at 1 starved AA's media queue (its log: "out of permits", 25 s queued).
                        // open-headunit grants audio 16 over USB.
                        val maxUnacked = when (message.channelId) {
                            AaServiceDiscovery.CHANNEL_MEDIA_SINK_VIDEO -> 10
                            AaServiceDiscovery.CHANNEL_MEDIA_SOURCE_MICROPHONE -> 1
                            else -> 16
                        }
                        val config = AaProtobuf.Writer()
                            .int(1, 2) // status = STATUS_READY
                            .int(2, maxUnacked)
                            .int(3, 0) // configuration_indices[0]
                            .build()
                        val responseId = if (message.channelId == AaServiceDiscovery.CHANNEL_MEDIA_SOURCE_MICROPHONE) {
                            AaFraming.MSG_MEDIA_SETUP
                        } else {
                            AaFraming.MSG_MEDIA_CONFIG
                        }
                        channel.sendChannelSpecific(message.channelId, responseId, config)
                        if (message.channelId == AaServiceDiscovery.CHANNEL_MEDIA_SINK_VIDEO) {
                            // VideoFocusNotification{focus=PROJECTED(1), unsolicited=false}:
                            // openauto's VideoMediaSinkService::sendVideoFocusIndication(),
                            // sent right after the setup response grants the phone focus
                            // to start encoding video.
                            val focus = AaProtobuf.Writer().int(1, 1).bool(2, false).build()
                            channel.sendChannelSpecific(message.channelId, AaFraming.MSG_MEDIA_VIDEO_FOCUS_NOTIFICATION, focus)
                        }
                    } else if (chId == AaFraming.MSG_MEDIA_START &&
                        (message.channelId == AaServiceDiscovery.CHANNEL_MEDIA_SINK_VIDEO ||
                            message.channelId == AaServiceDiscovery.CHANNEL_MEDIA_SINK_MEDIA_AUDIO)
                    ) {
                        // AVChannelStartIndication{session=1, config=2}: the
                        // `session` value must be echoed back in every
                        // AVMediaAckIndication on this channel (see sendAvAck).
                        val session = AaProtobuf.parse(chBody).firstOrNull { it.number == 1 }?.varint?.toInt() ?: 0
                        avAckSession[message.channelId] = session
                        logger.log("RX MEDIA_START on ${AaFraming.channelName(message.channelId)} session=$session")
                    } else if (chId == AaFraming.MSG_MEDIA_DATA && message.channelId == AaServiceDiscovery.CHANNEL_MEDIA_SINK_VIDEO) {
                        // handleMediaWithTimestampIndication(): payload is an
                        // 8-byte big-endian Timestamp (microseconds) followed
                        // by the raw H.264 Annex-B bytes.
                        if (chBody.size > 8) {
                            var ts = 0L
                            for (i in 0 until 8) ts = (ts shl 8) or (chBody[i].toLong() and 0xFF)
                            val frame = chBody.copyOfRange(8, chBody.size)
                            if (containsIdr(frame)) cachedKeyframe = frame
                            lastVideoFrameAt = System.currentTimeMillis()
                            hasReceivedVideo = true
                            mediaExecutor.execute { videoSink?.invoke(ts, frame) }
                        }
                        // Flow control (AVMediaAckIndication, 0x8004): our own
                        // MEDIA_SETUP response above grants max_unacked=1, so
                        // without acking every single frame the phone stalls
                        // after exactly one -- confirmed from a real exported
                        // log. Ack immediately on receipt rather than waiting
                        // for decode, so a slow/dropped decode doesn't also
                        // stall the phone's own send side.
                        sendAvAck(channel, message.channelId)
                    } else if (chId == AaFraming.MSG_MEDIA_CODEC_CONFIG && message.channelId == AaServiceDiscovery.CHANNEL_MEDIA_SINK_VIDEO) {
                        // SPS/PPS, no timestamp prefix (unlike MEDIA_MESSAGE_DATA) --
                        // feed as-is, timestamp 0, ahead of the IDR frame that follows it.
                        logger.log("RX MEDIA_CODEC_CONFIG on MEDIA_SINK_VIDEO (${chBody.size}B)")
                        if (chBody.isNotEmpty()) {
                            cachedCodecConfig = chBody
                            mediaExecutor.execute { videoSink?.invoke(0L, chBody) }
                        }
                        sendAvAck(channel, message.channelId)
                    } else if (message.channelId == AaServiceDiscovery.CHANNEL_MEDIA_SINK_MEDIA_AUDIO &&
                        (chId == AaFraming.MSG_MEDIA_CODEC_CONFIG || chId == AaFraming.MSG_MEDIA_DATA)
                    ) {
                        // Symmetric to video: AAC-LC codec config (csd-0) once,
                        // then timestamped frames. Without this the audio channel
                        // negotiates and streams fine but is silently discarded --
                        // which is the actual reason AA audio wasn't playing, not
                        // Bluetooth routing.
                        if (phoneMediaAudio) {
                            // Only PCM system sounds arrive here (music plays on the phone). Ack and drop.
                        } else if (chId == AaFraming.MSG_MEDIA_CODEC_CONFIG) {
                            logger.log("RX MEDIA_CODEC_CONFIG on MEDIA_SINK_MEDIA_AUDIO (${chBody.size}B)")
                            if (chBody.isNotEmpty()) {
                                // Reuse the existing player (setCodecConfig() no-ops if
                                // the bytes are unchanged) instead of always tearing down
                                // and recreating -- a codec-config message mid-stream
                                // (e.g. a voice prompt's own short-lived config arriving
                                // while music was already playing) was silently killing
                                // whatever was already playing and never resuming it,
                                // since music doesn't resend its own config afterward.
                                val player = audioPlayer
                                    ?: AaAudioPlayer(context, sampleRate = 48_000, channelCount = 2, preview = config.preview).also { audioPlayer = it }
                                audioExecutor.execute { player.setCodecConfig(chBody) }
                            }
                        } else if (chBody.size > 8) {
                            val pcm = chBody.copyOfRange(8, chBody.size)
                            audioExecutor.execute { audioPlayer?.feed(pcm) }
                        }
                        // Audio's own MEDIA_SETUP grants max_unacked=1 same as
                        // video -- the phone sends exactly one buffer then
                        // waits forever for this ack, which is the confirmed
                        // root cause of "no music, ever" (not a routing or
                        // AudioTrack-timing bug after all).
                        sendAvAck(channel, message.channelId)
                    } else if (chId == AaFraming.MSG_MEDIA_MICROPHONE_REQUEST &&
                        message.channelId == AaServiceDiscovery.CHANNEL_MEDIA_SOURCE_MICROPHONE
                    ) {
                        // MicrophoneRequest{open=1 bool}. Reply MicrophoneResponse{status=1,
                        // session_id=2} on the SAME id (aasdk quirk, see AaFraming), then
                        // start/stop capture -- openauto's onMediaSourceOpenRequest()/
                        // onMediaSourceOpenSuccess() flow.
                        val open = AaProtobuf.parse(chBody).firstOrNull { it.number == 1 }?.varint == 1L
                        logger.log("RX MICROPHONE_REQUEST open=$open")
                        val response = AaProtobuf.Writer().int(1, 0).int(2, 0).build() // status=SUCCESS, session_id=0
                        channel.sendChannelSpecific(message.channelId, AaFraming.MSG_MEDIA_MICROPHONE_REQUEST, response)
                        if (open) {
                            micCapture?.stop()
                            micCapture = AaMicCapture(context) { pcm ->
                                val payload = ByteArray(8 + pcm.size)
                                var ts = System.nanoTime() / 1000
                                for (i in 7 downTo 0) { payload[i] = (ts and 0xFF).toByte(); ts = ts shr 8 }
                                pcm.copyInto(payload, 8)
                                runCatching {
                                    channel.sendChannelSpecific(message.channelId, AaFraming.MSG_MEDIA_DATA, payload)
                                }
                            }.also { it.start() }
                        } else {
                            micCapture?.stop()
                            micCapture = null
                        }
                    } else if (chId == AaFraming.MSG_INPUT_BINDING_REQUEST && message.channelId == AaServiceDiscovery.CHANNEL_INPUT_SOURCE) {
                        // Key binding for steering-wheel buttons; openauto answers BindingResponse{status=OK(0)}.
                        logger.log("RX INPUT_BINDING_REQUEST -> OK")
                        channel.sendChannelSpecific(message.channelId, AaFraming.MSG_INPUT_BINDING_RESPONSE, AaProtobuf.Writer().int(1, 0).build())
                    } else if (chId == AaFraming.MSG_SENSOR_REQUEST && message.channelId == AaServiceDiscovery.CHANNEL_SENSOR) {
                        // SensorRequest{type=1, min_update_period=2}. Reply
                        // SensorStartResponseMessage{status=0} on
                        // SENSOR_MESSAGE_RESPONSE (32770).
                        val fields = AaProtobuf.parse(chBody)
                        val sensorType = fields.firstOrNull { it.number == 1 }?.varint
                        logger.log("RX SENSOR_MESSAGE_REQUEST on SENSOR type=$sensorType")
                        val response = AaProtobuf.Writer().int(1, 0).build() // status = STATUS_SUCCESS
                        channel.sendChannelSpecific(message.channelId, AaFraming.MSG_SENSOR_RESPONSE, response)
                        if (sensorType == SENSOR_NIGHT_MODE) {
                            nightSensorStarted = true
                            sendNightIfChanged()
                        }
                    }
                    continue
                }
                val id = AaFraming.controlMessageId(message.payload)
                val body = message.payload.copyOfRange(2, message.payload.size)
                logger.log("MESSAGE ${AaFraming.controlMessageName(id ?: -1)} (${body.size}B, ${message.frames} frame(s))")
                logger.logBytes("RXBODY", body, minOf(body.size, 256))
                when (id) {
                    AaFraming.MSG_SERVICE_DISCOVERY_REQUEST -> {
                        decodeDiscoveryRequest(body)
                        logger.log("rung 3 reached -- phone asked for service discovery")
                        everEstablished = true
                        runCatching { onEstablished?.invoke() }
                        val discoveryConfig = AaServiceDiscovery.configFromSettings(context, preview = config.preview)
                        phoneMediaAudio = discoveryConfig.phoneMediaAudio
                        val response = AaServiceDiscovery.build(discoveryConfig)
                        logger.log("TX SERVICE_DISCOVERY_RESPONSE ${response.size}B (${AaServiceDiscovery.describe(discoveryConfig)})")
                        logger.logBytes("TXBODY", response, response.size)
                        channel.sendControlEncrypted(AaFraming.MSG_SERVICE_DISCOVERY_RESPONSE, response)
                    }
                    AaFraming.MSG_PING_REQUEST -> channel.sendControlEncrypted(AaFraming.MSG_PING_RESPONSE, body)
                    AaFraming.MSG_SHUTDOWN_REQUEST -> {
                        // Confirmed from mrmees/open-android-auto's own protocol
                        // docs (interactions/05-session-maintenance-teardown.md,
                        // GPL-3.0 -- paraphrased, not copied): a dedicated
                        // shutdown handshake exists separate from a plain
                        // connection drop, with a reason field distinguishing
                        // "user tapped Exit" from other causes. We previously
                        // didn't handle this message at all, so every exit --
                        // including a deliberate one via AA's own exit control --
                        // looked identical to a dropped link to run()'s
                        // auto-reconnect loop, which just reconnected right back
                        // (the actual reason "AA's exit button doesn't work").
                        // Acknowledge, then stop for real instead of reconnecting.
                        val reason = AaProtobuf.parse(body).firstOrNull { it.number == 1 }?.varint
                        logger.log("RX SHUTDOWN_REQUEST reason=$reason -- acking and ending session (no auto-reconnect)")
                        channel.sendControlEncrypted(AaFraming.MSG_SHUTDOWN_RESPONSE, ByteArray(0))
                        running.set(false)
                    }
                    AaFraming.MSG_SHUTDOWN_RESPONSE -> {
                        logger.log("RX SHUTDOWN_RESPONSE -- phone closed the session cleanly")
                        draining = false
                        shutdownAcked.countDown()
                    }
                    AaFraming.MSG_AUDIO_FOCUS_REQUEST -> {
                        // AudioFocusRequest.audio_focus_type = 1 (AUDIO_FOCUS_RELEASE = 4).
                        // openauto's AndroidAutoEntity::onAudioFocusRequest(): release -> LOSS(3),
                        // anything else -> GAIN(1). AudioFocusNotification.focus_state = 1.
                        val requestType = AaProtobuf.parse(body).firstOrNull { it.number == 1 }?.varint
                        val focusState = if (requestType == 4L) 3 else 1
                        logger.log("RX AUDIO_FOCUS_REQUEST type=$requestType -> focus_state=$focusState")
                        val notification = AaProtobuf.Writer().int(1, focusState).build()
                        channel.sendControlEncrypted(AaFraming.MSG_AUDIO_FOCUS_NOTIFICATION, notification)
                    }
                    AaFraming.MSG_NAV_FOCUS_REQUEST -> {
                        // Unanswered, AA gives nav focus back to the car after 15 s and Maps ends the trip.
                        // openauto and libheadunit always grant PROJECTED (2).
                        val requestType = AaProtobuf.parse(body).firstOrNull { it.number == 1 }?.varint
                        logger.log("RX NAV_FOCUS_REQUEST type=$requestType -> granting projected")
                        channel.sendControlEncrypted(AaFraming.MSG_NAV_FOCUS_NOTIFICATION, AaProtobuf.Writer().int(1, 2).build())
                    }
                }
            }
            true
        } catch (e: Exception) {
            logger.log("failed: ${e.javaClass.simpleName}: ${e.message}")
            Log.w(TAG, "session failed", e)
            false
        } finally {
            isConnectionActive = false
            activeChannel = null
            cachedCodecConfig = null
            cachedKeyframe = null
            micCapture?.stop()
            micCapture = null
            audioPlayer?.stop()
            audioPlayer = null
            runCatching { socket?.close() }
            socket = null
        }
    }

    /**
     * Drives [SSLEngine] over AA frames. Each `wrap()` output becomes the body
     * of a PLAIN `MESSAGE_ENCAPSULATED_SSL` message; each such message we
     * receive is fed back into `unwrap()`.
     *
     * Important detail: one AA message can carry several TLS records, and
     * `unwrap` may stop mid-buffer. Leftover bytes are kept in [pending] and
     * fed back in before reading another frame — discarding them deadlocks
     * the handshake (first hardware run did exactly that: ServerHello
     * arrived, unwrap said NEED_UNWRAP, and we blocked waiting for data we
     * had already been given).
     */
    private fun handshake(engine: SSLEngine, input: DataInputStream, output: java.io.OutputStream) {
        val app = ByteBuffer.allocate(engine.session.applicationBufferSize)
        val netOut = ByteBuffer.allocate(engine.session.packetBufferSize)
        val empty = ByteBuffer.allocate(0)
        var pending = ByteArray(0)

        fun send(record: ByteArray) {
            if (record.isEmpty()) return
            val message = AaFraming.controlMessage(AaFraming.MSG_ENCAPSULATED_SSL, record)
            logger.log("TX ENCAPSULATED_SSL (${record.size}B)")
            logger.logBytes("TX", record, minOf(record.size, 64))
            output.write(message)
            output.flush()
        }

        /** Next encapsulated-SSL record body, skipping anything else. */
        fun nextRecord(): ByteArray {
            while (true) {
                val frame = readFrame(input)
                val id = AaFraming.controlMessageId(frame.payload)
                if (id == AaFraming.MSG_ENCAPSULATED_SSL) {
                    val record = frame.payload.copyOfRange(2, frame.payload.size)
                    logger.log("RX ENCAPSULATED_SSL (${record.size}B)")
                    return record
                }
                logger.log("skipping ${AaFraming.controlMessageName(id ?: -1)} during handshake")
                logFrame(-1, frame)
            }
        }

        var rounds = 0
        while (running.get() && rounds++ < 120) {
            when (engine.handshakeStatus) {
                SSLEngineResult.HandshakeStatus.NEED_TASK -> engine.delegatedTask?.run()

                SSLEngineResult.HandshakeStatus.NEED_WRAP -> {
                    netOut.clear()
                    val result = engine.wrap(empty, netOut)
                    netOut.flip()
                    val record = ByteArray(netOut.remaining())
                    netOut.get(record)
                    logger.log("wrap ${result.status} -> ${record.size}B")
                    send(record)
                }

                SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> {
                    var progressed = false
                    while (true) {
                        if (pending.isEmpty()) {
                            pending = nextRecord()
                        }
                        val buf = ByteBuffer.wrap(pending)
                        app.clear()
                        val result = engine.unwrap(buf, app)
                        val consumed = buf.position()
                        if (consumed > 0) {
                            pending = pending.copyOfRange(consumed, pending.size)
                            progressed = true
                        }
                        logger.log(
                            "unwrap ${result.status}/${result.handshakeStatus} " +
                                "consumed=$consumed produced=${result.bytesProduced()} left=${pending.size}"
                        )
                        if (result.status == SSLEngineResult.Status.BUFFER_UNDERFLOW) {
                            pending += nextRecord()
                            continue
                        }
                        if (result.status == SSLEngineResult.Status.CLOSED) {
                            throw SSLException("engine closed during handshake")
                        }
                        if (engine.handshakeStatus != SSLEngineResult.HandshakeStatus.NEED_UNWRAP) break
                        if (consumed == 0 && pending.isNotEmpty()) break
                    }
                    if (!progressed && pending.isEmpty()) {
                        throw SSLException("handshake stalled with nothing buffered")
                    }
                }

                SSLEngineResult.HandshakeStatus.FINISHED,
                SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING -> return

                null -> return
            }
        }
        throw SSLException("handshake did not complete in $rounds rounds")
    }

    /** ServiceDiscoveryRequest.proto: label_text=4, device_name=5, phone_info=6. */
    private fun decodeDiscoveryRequest(body: ByteArray) {
        val fields = runCatching { AaProtobuf.parse(body) }.getOrElse {
            logger.log("  could not parse discovery request: ${it.message}")
            return
        }
        for (f in fields) {
            when (f.number) {
                4 -> logger.log("  label_text='${AaProtobuf.utf8(f)}'")
                5 -> logger.log("  device_name='${AaProtobuf.utf8(f)}'")
                6 -> logger.log("  phone_info (${f.bytes?.size ?: 0}B)")
                1, 2, 3 -> logger.log("  icon field ${f.number} (${f.bytes?.size ?: 0}B)")
            }
        }
    }

    /** ServiceDiscoveryResponse.proto: field 1 = repeated service.Service. */
    private fun decodeServiceDiscovery(body: ByteArray) {
        val fields = runCatching { AaProtobuf.parse(body) }.getOrElse {
            logger.log("  could not parse discovery response: ${it.message}")
            return
        }
        logger.log("SERVICE_DISCOVERY_RESPONSE ${body.size}B, ${fields.count { it.number == 1 }} channel(s)")
        for (f in fields) {
            if (f.number != 1 || f.bytes == null) continue
            val channel = runCatching { AaProtobuf.parse(f.bytes) }.getOrNull() ?: continue
            val id = channel.firstOrNull { it.number == 1 }?.varint ?: -1
            val kinds = channel.filter { it.number in 2..14 }
                .joinToString(",") { serviceName(it.number.toInt()) }
            logger.log("  channel id=$id other_fields=[$kinds]")
        }
        val displayName = fields.firstOrNull { it.number == 14 }?.let { AaProtobuf.utf8(it) }
        if (!displayName.isNullOrBlank()) logger.log("  display_name=$displayName")
        logger.log("rung 3 reached -- channel list above")
        Log.i(TAG, "service discovery decoded")
    }

    /** aap_protobuf/service/Service.proto field numbers. */
    private fun serviceName(field: Int): String = when (field) {
        2 -> "sensor_source"
        3 -> "media_sink"
        4 -> "input_source"
        5 -> "media_source"
        6 -> "bluetooth"
        7 -> "radio"
        8 -> "navigation_status"
        9 -> "media_playback"
        10 -> "phone_status"
        11 -> "media_browser"
        12 -> "vendor_extension"
        13 -> "generic_notification"
        14 -> "wifi_projection"
        else -> "field$field"
    }

    private fun logPeer(engine: SSLEngine) {
        val session = engine.session
        val peer = runCatching { session.peerCertificates }.getOrNull()
        if (peer.isNullOrEmpty()) {
            logger.log("  peer: no certificate (phone does not authenticate us at TLS layer)")
        } else {
            peer.forEachIndexed { i, cert ->
                (cert as? X509Certificate)?.let {
                    logger.log("  peer[$i] '${it.subjectX500Principal.name}' sha256=${AaIdentity.fingerprint(it)}")
                }
            }
        }
        runCatching { session.localCertificates }.getOrNull()?.forEachIndexed { i, cert ->
            (cert as? X509Certificate)?.let {
                logger.log("  local[$i] '${it.subjectX500Principal.name}'")
            }
        }
    }

    private fun readFrame(input: DataInputStream): AaFraming.Frame {
        val header = ByteArray(2)
        input.readFully(header)
        val channelId = header[0].toInt() and 0xFF
        val flags = header[1].toInt() and 0xFF
        val frameType = flags and 0x03
        val messageType = flags and 0x04
        val encryption = flags and 0x08

        val sizeField = if (frameType == AaFraming.FRAME_FIRST) 6 else 2
        val sizeBytes = ByteArray(sizeField)
        input.readFully(sizeBytes)
        val frameSize = AaFraming.u16(sizeBytes, 0)
        val totalSize = if (sizeField == 6) AaFraming.u32(sizeBytes, 2) else frameSize

        val payload = ByteArray(frameSize)
        if (frameSize > 0) input.readFully(payload)
        return AaFraming.Frame(channelId, frameType, messageType, encryption, frameSize, totalSize, payload)
    }

    private fun logFrame(index: Int, f: AaFraming.Frame) {
        val enc = if (f.isPlain) "PLAIN" else "ENCRYPTED"
        val mtype = if (f.messageType == AaFraming.MESSAGE_CONTROL) "CONTROL" else "SPECIFIC"
        val named = if (f.channelId == AaFraming.CHANNEL_CONTROL && f.isPlain) {
            AaFraming.controlMessageId(f.payload)?.let { " ${AaFraming.controlMessageName(it)}" } ?: ""
        } else ""
        logger.log(
            "RX #$index ch=${AaFraming.channelName(f.channelId)}(${f.channelId}) " +
                "type=${AaFraming.frameTypeName(f.frameType)} $enc $mtype size=${f.frameSize}$named"
        )
        if (f.payload.isNotEmpty()) logger.logBytes("RX", f.payload, minOf(f.payload.size, 256))
    }
}
