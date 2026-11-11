package dev.caraalink.link

import android.content.Context

import android.hardware.usb.UsbAccessory
import android.util.Log
import dev.caraalink.aa.AndroidAutoServer
import dev.caraalink.connection.ConnectionPhase
import dev.caraalink.connection.ConnectionStateHolder

import dev.caraalink.diagnostics.DiagnosticLogger
import dev.caraalink.usb.UsbAccessoryConnection
import dev.caraalink.video.CarDisplay
import dev.caraalink.video.CarScreen
import dev.caraalink.video.CarVideoEncoder
import org.json.JSONObject
import java.util.ArrayDeque

/**
 * One connection to the head unit: ADB transport, PXC control, mirror
 * control/data, H.264 encoder and what's on the car screen.
 */
class CarSession(
    private val context: Context,
    private val accessory: UsbAccessory,
    private val onEnded: (String) -> Unit,
) : MirrorHost {

    private val logger = DiagnosticLogger(context, "car-session")
    private var transport: AdbTransport? = null
    private var config: CaptureConfig? = null
    private var lastBitRate: Int = -1
    private var encoder: CarVideoEncoder? = null
    private var pxc: PxcChannel? = null
    private val features = CarFeatures(context)
    private val display = CarDisplay(context, onOpenAa = { openAndroidAuto() })

    /** The driver left Android Auto on purpose; stay on Closed until they tap to reopen. */
    private fun closeAa() {
        aaWatchdog.removeCallbacksAndMessages(null)
        dev.caraalink.aa.AaSessionProbe.active?.stop()
        display.showStatus(CarScreen.CLOSED)
    }

    @Volatile private var released = false

    private val frameLock = Any()
    @Volatile private var dataStream: MirrorStream? = null
    private var codecConfig: ByteArray? = null
    private var configSent = false
    private val pullQueue = ArrayDeque<ByteArray>()
    private var framesSent = 0L
    private var framesEncoded = 0L
    private var frameRequests = 0L
    private var pendingRequests = 0
    private var waitingForKey = true
    private var lastKeyFrame: ByteArray? = null

    fun start(): Boolean {
        val conn = UsbAccessoryConnection.open(context, accessory) ?: run {
            logger.log("openAccessory() returned null")
            return false
        }
        logger.log("Opened accessory ${accessory.manufacturer}/${accessory.model}")
        ConnectionStateHolder.update {
            it.copy(phase = ConnectionPhase.ATTACHED, accessoryManufacturer = accessory.manufacturer,
                accessoryModel = accessory.model, lastError = null)
        }
        features.start()
        val pxc = PxcChannel(logger, { info -> onClientInfo(info) }, features::replyBody).also { this.pxc = it }
        val ctrl = MirrorControlChannel(logger, this, context)
        val data = MirrorDataChannel(logger, this)
        transport = AdbTransport(conn, logger, { service ->
            when (service) {
                "tcp:10922" -> pxc
                "tcp:10921" -> ctrl
                "tcp:10920" -> data
                else -> null
            }
        }) { reason ->
            Log.i(TAG, "transport closed: $reason")
            release()
            ConnectionStateHolder.update { it.copy(phase = ConnectionPhase.ERROR, lastError = reason) }
            onEnded(reason)
        }.also { it.start() }
        return true
    }

    fun stop() {
        transport?.stop("stopped by user") ?: release()
    }

    private fun release() {
        released = true
        aaWatchdog.removeCallbacksAndMessages(null)
        pollThread?.interrupt()
        pollThread = null
        AndroidAutoServer.cancelNeedsTap(context)
        dev.caraalink.aa.AaReconnectBus.onVideoSettingsChanged = null
        dev.caraalink.aa.AaSessionProbe.active?.stop()
        aaDecoder?.stop()
        aaDecoder = null
        android.os.Handler(android.os.Looper.getMainLooper()).post { features.stop() }
        encoder?.release()
        encoder = null
        display.release()
        synchronized(frameLock) { dataStream = null; pullQueue.clear() }
    }

    private fun onClientInfo(info: JSONObject) {
        logger.log("Head unit: ${info.optString("HUName")} screenType=${info.optInt("screenType")} " +
            "mirror=${info.optBoolean("supportScreenMirroring")} touch=${info.optBoolean("supportScreenTouch")}")
        ConnectionStateHolder.update { it.copy(carName = info.optString("carBrand").ifBlank { null }) }
    }

    // --- car home screen actions ---

    private var aaDecoder: dev.caraalink.aa.AaVideoDecoder? = null

    /**
     * Starts the real AA receiver (dev.caraalink.aa.AaSessionProbe -- the
     * old AaTlsReceiver above this comment used to be wired here, but it's
     * a dead end that never got past a raw TLS handshake; superseded) and
     * shows its decoded video + live touch on the car's own screen, at
     * whatever resolution Settings > Video actually negotiates. Audio plays
     * on the *phone's* speaker -- CarbitLink's own wire protocol doesn't
     * carry audio to the car at all yet (not implemented anywhere, mirroring
     * included), so that's a separate, later piece of work.
     */
    // `active` is null between an old probe stopping and the new one starting on the thread
    // below, so two calls could race through; this flag closes that gap.
    @Volatile private var openingAa = false

    private fun openAndroidAuto() {
        if (released) return
        if (dev.caraalink.aa.AaSessionProbe.active != null) {
            showAaSurface()
            return
        }
        synchronized(this) {
            if (openingAa) {
                logger.log("openAndroidAuto: already opening -- ignoring concurrent call")
                return
            }
            openingAa = true
        }
        display.showStatus(CarScreen.STARTING)
        logger.log("openAndroidAuto: checking 127.0.0.1:5277 before starting")
        Thread({
            if (!AndroidAutoServer.isRunning()) {
                logger.log("openAndroidAuto: server not listening -- waiting for the driver to start it")
                openingAa = false
                main.post {
                    if (released) return@post
                    display.showStatus(CarScreen.WAITING)
                    AndroidAutoServer.notifyNeedsTap(context)
                    startServerPoll(SERVER_POLL_MS)
                }
                return@Thread
            }
            // Gearhead tears down the previous session for ~2s after an exit; don't race it.
            Thread.sleep(2000)
            if (!mayAutoOpenAa(released, display.screen)) {
                logger.log("openAndroidAuto: unplugged or closed while starting -- not opening")
                openingAa = false
                return@Thread
            }
            logger.log("openAndroidAuto: starting AA session probe")
            val probe = dev.caraalink.aa.AaSessionProbe(context)
            probe.onEstablished = { AndroidAutoServer.cancelNeedsTap(context) }
            probe.onGaveUp = { main.post { onAaNotResponding() } }
            probe.start()
            openingAa = false
            main.post { if (!released) showAaSurface() }
        }, "AA-server-check").start()
    }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var pollThread: Thread? = null

    /** Server listening but every handshake failed: tell the driver, keep retrying. */
    private fun onAaNotResponding() {
        if (!mayAutoOpenAa(released, display.screen)) return
        logger.log("AA handshake retries used up -- server not responding")
        display.showStatus(CarScreen.NOT_RESPONDING)
        AndroidAutoServer.notifyNeedsTap(context, notResponding = true)
        startServerPoll(NOT_RESPONDING_RETRY_MS)
    }

    /** While the car waits on the server, check it off the main thread and reopen Android Auto once it's up. */
    private fun startServerPoll(intervalMs: Long) {
        if (released || pollThread?.isAlive == true) return
        pollThread = Thread({
            try {
                while (true) {
                    Thread.sleep(intervalMs)
                    val screen = display.screen
                    val waiting = screen == CarScreen.WAITING || screen == CarScreen.NOT_RESPONDING
                    if (display.mode != CarDisplay.Mode.STATUS || !waiting) return@Thread
                    if (AndroidAutoServer.shouldAutoOpen(screen, AndroidAutoServer.isRunning())) {
                        main.post {
                            if (!released && display.mode == CarDisplay.Mode.STATUS &&
                                AndroidAutoServer.shouldAutoOpen(display.screen, true)
                            ) openAndroidAuto()
                        }
                        return@Thread
                    }
                }
            } catch (_: InterruptedException) {
            }
        }, "AA-server-poll").also { it.isDaemon = true; it.start() }
    }

    /**
     * Auto-recovery for a frozen (not disconnected) AA session: the TCP
     * socket can stay open with no data ever arriving again (head unit
     * reboot on the AA side, a wedged gearhead service, etc.) without
     * throwing anything AaSessionProbe's own reconnect logic would catch --
     * that logic only fires on an actual socket/TLS error. This polls
     * [AaSessionProbe.lastVideoFrameAt] and, if it's gone stale while the
     * car is actually showing the AA screen, tears down and restarts the
     * whole session instead of leaving a dead picture up indefinitely.
     */
    private val aaWatchdog = android.os.Handler(android.os.Looper.getMainLooper())
    // Confirmed from a real log: when gearhead's own head-unit-server is
    // wedged/recovering, a fixed 10s stall timeout fires faster than it can
    // actually come back -- every restarted probe's FIRST attempt alone (TLS
    // handshake against an unresponsive peer) was eating most of that 10s,
    // so the watchdog kept killing and replacing the probe (always logging
    // "attempt 1/8", never getting to attempt 2) every ~10-15s for 42
    // straight seconds before one attempt happened to land. That's a worse,
    // self-inflicted version of "have to restart head unit server" -- back
    // off the stall threshold on each consecutive watchdog-triggered restart
    // instead of hammering it at the same fixed interval forever.
    private var watchdogRestartStreak = 0
    private var inactiveSince = 0L
    private val aaWatchdogTick = object : Runnable {
        override fun run() {
            val probe = dev.caraalink.aa.AaSessionProbe.active
            if (display.mode == CarDisplay.Mode.AA) {
                if (probe == null) {
                    // Session ended on its own (AA's Exit sends SHUTDOWN_REQUEST); don't leave a frozen frame up.
                    logger.log("AA watchdog: session ended on its own -- showing Closed")
                    watchdogRestartStreak = 0
                    display.showStatus(CarScreen.CLOSED)
                } else {
                    val staleMs = System.currentTimeMillis() - probe.lastVideoFrameAt
                    if (probe.isConnectionActive) inactiveSince = 0L
                    if (probe.hasReceivedVideo && !probe.isConnectionActive) {
                        // The probe owns reconnect backoff, but if it lasts this long the server is gone:
                        // fall back to the Waiting flow instead of leaving the last frame frozen.
                        val now = System.currentTimeMillis()
                        if (inactiveSince == 0L) inactiveSince = now
                        if (lostServer(connectionActive = false, inactiveForMs = now - inactiveSince)) {
                            logger.log("AA watchdog: no connection for ${now - inactiveSince}ms -- server lost")
                            inactiveSince = 0L
                            probe.stop()
                            if (mayAutoOpenAa(released, display.screen)) openAndroidAuto()
                            return
                        }
                        aaWatchdog.postDelayed(this, AA_WATCHDOG_CHECK_MS)
                        return
                    }
                    if (!probe.hasReceivedVideo) {
                        val startupMs = staleMs
                        if (startupMs > AA_WATCHDOG_STARTUP_MS) {
                            // The server accepts but never answers ("goes deaf"); say so instead of a black screen.
                            logger.log("AA watchdog: no video after ${startupMs}ms startup grace -- not responding")
                            dev.caraalink.diagnostics.PerfStats.aaReconnects++
                            probe.stop()
                            onAaNotResponding()
                            return
                        }
                        aaWatchdog.postDelayed(this, AA_WATCHDOG_CHECK_MS)
                        return
                    }
                    val stallThreshold = (AA_WATCHDOG_STALL_MS * (1 shl watchdogRestartStreak.coerceAtMost(3)))
                        .coerceAtMost(AA_WATCHDOG_STALL_MAX_MS)
                    if (staleMs > stallThreshold) {
                        logger.log("AA watchdog: no video frame in ${staleMs}ms (threshold ${stallThreshold}ms, " +
                            "streak $watchdogRestartStreak) -- restarting session")
                        dev.caraalink.diagnostics.PerfStats.aaReconnects++
                        watchdogRestartStreak++
                        probe.stop()
                        if (mayAutoOpenAa(released, display.screen)) openAndroidAuto()
                    } else if (staleMs < AA_WATCHDOG_CHECK_MS) {
                        // A frame actually arrived since the last tick -- the
                        // session is genuinely healthy again, not just early
                        // in its grace window. Reset the backoff.
                        watchdogRestartStreak = 0
                    }
                }
            }
            aaWatchdog.postDelayed(this, AA_WATCHDOG_CHECK_MS)
        }
    }

    private fun showAaSurface() {
        aaWatchdog.removeCallbacks(aaWatchdogTick)
        aaWatchdog.postDelayed(aaWatchdogTick, AA_WATCHDOG_CHECK_MS)
        // No mid-session resolution/DPI renegotiation exists in AA (confirmed,
        // see roadmap.md) -- the only way to apply a changed video setting is
        // a full reconnect, so arm the bus SettingsActivity signals through.
        dev.caraalink.aa.AaReconnectBus.onVideoSettingsChanged = {
            logger.log("AA video settings changed -- reconnecting session")
            dev.caraalink.aa.AaSessionProbe.active?.stop()
            // A warm restart needs a moment for gearhead to tear down the old session.
            aaWatchdog.postDelayed({ if (mayAutoOpenAa(released, display.screen)) openAndroidAuto() }, 1500)
        }
        val config = dev.caraalink.aa.AaServiceDiscovery.configFromSettings(context)
        val fillMode = dev.caraalink.settings.AppSettings.aaFillMode(context)
        logger.log(
            "AA config: resolution=${config.resolution.width}x${config.resolution.height} " +
                "fps=${config.frameRate} density=${config.density} " +
                "widthMargin=${config.widthMargin} heightMargin=${config.heightMargin} " +
                "autoCrop=${dev.caraalink.settings.AppSettings.aaAutoCrop(context)} fillMode=$fillMode " +
                "carScreen=${dev.caraalink.link.CarScreenInfo.width}x${dev.caraalink.link.CarScreenInfo.height}",
        )
        // A restart dismisses the old presentation while staying in AA; only a real exit
        // (showStatus sets mode away from AA first) should stop the probe in onSurface(null).
        val myProbe = dev.caraalink.aa.AaSessionProbe.active
        display.showAa(
            videoWidth = config.resolution.width,
            videoHeight = config.resolution.height,
            widthMargin = config.widthMargin,
            heightMargin = config.heightMargin,
            onSurface = { surface ->
                if (surface != null) {
                    val dec = dev.caraalink.aa.AaVideoDecoder(config.resolution.width, config.resolution.height)
                    dec.start(surface)
                    aaDecoder = dec
                    myProbe?.attachVideoSink { ts, nal -> aaDecoder?.feed(ts, nal) }
                } else {
                    // End the session for real when leaving AA, not on a restart's teardown.
                    if (display.mode != dev.caraalink.video.CarDisplay.Mode.AA &&
                        dev.caraalink.aa.AaSessionProbe.active === myProbe
                    ) {
                        myProbe?.stop()
                    }
                    aaDecoder?.stop()
                    aaDecoder = null
                }
            },
            onTouch = { pointers, actionIndex, action ->
                dev.caraalink.aa.AaSessionProbe.active?.sendTouch(pointers, actionIndex, action)
            },
        )
    }

    // --- MirrorHost ---

    override fun onCaptureConfig(config: CaptureConfig) {
        val previous = this.config
        this.config = config
        CarScreenInfo.update(config.width, config.height)
        val fps = if (config.wantFps > 0) config.wantFps else 30
        // Settings > Video > bitrate used to be decorative -- nothing ever
        // read it, the encoder always used either the car's own requested
        // bitrate or DEFAULT_BITRATE. Now it's a floor: the car's request
        // (it may know its own decode limits) is still respected if it's
        // already asking for more than the setting.
        val settingsBitrate = dev.caraalink.settings.AppSettings.videoBitrateKbps(context) * 1000
        val carBitrate = if (config.bitRate > 0) config.bitRate else DEFAULT_BITRATE
        val bitRate = maxOf(carBitrate, settingsBitrate)
        // The dongle re-sends this CONFIG_CAPTURE control message on its own
        // periodically -- not just on an actual resolution/orientation change
        // -- confirmed from a real log: it showed up twice (~9s apart) during
        // a single unbroken AA session with nothing touched on either end.
        // Tearing down and reallocating the encoder/display on every one of
        // those stomped the surface AA's own video was actively streaming
        // into (BufferQueue abandoned, frames dropped), which is the visible
        // glitch/artifacting the owner reported -- and happened right before
        // that AA session died outright. Skip the rebuild when nothing the
        // encoder actually cares about changed.
        if (encoder != null && previous == config && bitRate == lastBitRate) {
            logger.log("CONFIG_CAPTURE repeated with no change -- not rebuilding encoder")
            return
        }
        lastBitRate = bitRate
        encoder?.release()
        val enc = CarVideoEncoder(config.encodeWidth, config.encodeHeight, fps, bitRate, ::onEncodedFrame)
        try {
            enc.start()
        } catch (e: Exception) {
            logger.log("encoder start failed: $e")
            return
        }
        synchronized(frameLock) {
            codecConfig = null
            configSent = false
            pullQueue.clear()
        }
        encoder = enc
        display.attach(enc.inputSurface, config.encodeWidth, config.encodeHeight)
        // First point the car display exists; once per session, since every CONFIG_CAPTURE lands here.
        if (!autoLaunchedAa) {
            autoLaunchedAa = true
            openAndroidAuto()
        }
    }

    @Volatile private var autoLaunchedAa = false

    override fun onStartVideo(): Boolean {
        encoder?.requestKeyFrame()
        return encoder != null
    }

    override fun onTouch(action: Int, x: Int, y: Int) {
        val c = config ?: return
        val enc = encoder ?: return
        // Car coordinates are in its own screen size; the encoder may be a few pixels smaller.
        val fx = x * enc.width.toFloat() / c.width
        val fy = y * enc.height.toFloat() / c.height
        display.touch(action, fx, fy)
    }

    override fun onKey(key: Int) {
        logger.log("car key $key")
        if (key == MirrorControlChannel.KEY_HOME || key == MirrorControlChannel.KEY_BACK) closeAa()
    }

    override fun onDataStreamOpen(stream: MirrorStream) {
        synchronized(frameLock) {
            dataStream = stream
            configSent = false
            waitingForKey = true
            pendingRequests = 0
            pullQueue.clear()
            // A starvation resend must never hand a new stream the previous stream's keyframe.
            lastKeyFrame = null
        }
        logger.log("data stream open; encoded so far=$framesEncoded, requesting keyframe")
        ConnectionStateHolder.update { it.copy(phase = ConnectionPhase.STREAMING) }
        encoder?.requestKeyFrame()
    }

    override fun onDataStreamClosed(stream: MirrorStream) {
        synchronized(frameLock) { if (dataStream === stream) dataStream = null }
        logger.log("data stream closed; requests=$frameRequests sent=$framesSent encoded=$framesEncoded")
    }

    override fun onFrameRequested(stream: MirrorStream) {
        frameRequests++
        if (frameRequests <= 3 || frameRequests % 100 == 0L) logger.log("frame request #$frameRequests (queued=${pullQueue.size})")
        synchronized(frameLock) {
            // CarbitLink blocks until the encoder has a frame instead of answering
            // "none", so hold the request and answer when the next frame lands.
            val next = pullQueue.poll()
            if (next == null) {
                pendingRequests++
                scheduleStarvationCheck(stream)
                return
            }
            sendFrameLocked(stream, next)
        }
    }

    private val watchdog = android.os.Handler(android.os.Looper.getMainLooper())

    /** If the encoder stalls, answer with the last keyframe so the car never times out. Caller holds [frameLock]. */
    private fun scheduleStarvationCheck(stream: MirrorStream) {
        val sentBefore = framesSent
        watchdog.postDelayed({
            synchronized(frameLock) {
                val key = lastKeyFrame
                // Anything sent since this request means the encoder is alive and the request will be answered.
                if (framesSent == sentBefore && pendingRequests > 0 && dataStream === stream && key != null) {
                    pendingRequests--
                    sendFrameLocked(stream, key)
                    // P-frames after an old keyframe decode against the wrong picture, so restart on a fresh one.
                    waitingForKey = true
                    encoder?.requestKeyFrame()
                }
            }
        }, ENCODER_STALL_MS)
    }

    /** Caller holds [frameLock]. Codec config always precedes the first frame. */
    private fun sendFrameLocked(stream: MirrorStream, frame: ByteArray) {
        val cfg = codecConfig
        if (!configSent && cfg != null) {
            stream.write(MirrorDataChannel.frame(cfg) + MirrorDataChannel.frame(frame))
            configSent = true
        } else {
            stream.write(MirrorDataChannel.frame(frame))
        }
        framesSent++
        if (framesSent <= 3 || framesSent % 300 == 0L) logger.log("frame sent #$framesSent (${frame.size} bytes)")
    }

    private fun onEncodedFrame(data: ByteArray, isConfig: Boolean, isKey: Boolean) {
        framesEncoded++
        if (!isConfig) dev.caraalink.diagnostics.PerfStats.recordMirrorFrame(data.size)
        if (framesEncoded <= 2) logger.log("encoder output #$framesEncoded config=$isConfig key=$isKey ${data.size} bytes")
        val pull = (config?.wantFps ?: 0) == 0
        synchronized(frameLock) {
            if (isConfig) {
                codecConfig = data
                configSent = false
                return
            }
            if (isKey) lastKeyFrame = data
            val stream = dataStream ?: return
            // The car's decoder needs to start on a keyframe.
            if (waitingForKey) {
                if (!isKey) { encoder?.requestKeyFrame(); return }
                waitingForKey = false
            }
            if (pull) {
                if (pendingRequests > 0) {
                    pendingRequests--
                    sendFrameLocked(stream, data)
                    return
                }
                if (pullQueue.size >= MAX_PULL_QUEUE) {
                    if (!isKey) { encoder?.requestKeyFrame(); return }
                    dev.caraalink.diagnostics.PerfStats.mirrorFramesDropped += pullQueue.size
                    pullQueue.clear()
                }
                pullQueue.add(data)
                return
            }
            if (stream.backlogBytes() > MAX_PUSH_BACKLOG) {
                if (!isKey) { encoder?.requestKeyFrame(); return }
                stream.clearBacklog()
            }
            sendFrameLocked(stream, data)
        }
    }

    companion object {
        private const val TAG = "CarSession"
        private const val DEFAULT_BITRATE = 3_000_000
        private const val APPSTATUS_BACKGROUND = 0x20030
        private const val MAX_PULL_QUEUE = 8
        private const val ENCODER_STALL_MS = 500L
        private const val MAX_PUSH_BACKLOG = 2 * 1024 * 1024
        private const val AA_WATCHDOG_CHECK_MS = 5_000L
        private const val AA_WATCHDOG_STALL_MS = 10_000L
        private const val AA_WATCHDOG_STARTUP_MS = 60_000L
        private const val AA_WATCHDOG_STALL_MAX_MS = 60_000L
        private const val SERVER_POLL_MS = 2_000L
        private const val NOT_RESPONDING_RETRY_MS = 5_000L

        private const val LOST_SERVER_MS = 30_000L

        /** Automatic reopens (restarts, retries, polls) must not undo an unplug or a deliberate exit. */
        internal fun mayAutoOpenAa(released: Boolean, screen: CarScreen): Boolean =
            !released && screen != CarScreen.CLOSED

        /** Reconnect backoff that long means Android Auto's server is gone, not just restarting. */
        internal fun lostServer(connectionActive: Boolean, inactiveForMs: Long): Boolean =
            !connectionActive && inactiveForMs >= LOST_SERVER_MS

        // AA's head unit server never answers again after a connection we close, so probe by bind, not connect.
        internal fun isListening(port: Int): Boolean = runCatching {
            java.net.ServerSocket(port, 1, java.net.InetAddress.getByName("127.0.0.1")).close()
            false
        }.getOrElse { it is java.net.BindException }
    }
}
