package dev.caraalink.link

import android.content.Context
import android.graphics.Color
import android.hardware.usb.UsbAccessory
import android.media.projection.MediaProjection
import android.util.Log
import dev.caraalink.connection.ConnectionPhase
import dev.caraalink.connection.ConnectionStateHolder
import dev.caraalink.R
import dev.caraalink.diagnostics.DiagnosticLogger
import dev.caraalink.usb.UsbAccessoryConnection
import dev.caraalink.video.CarDisplay
import dev.caraalink.video.CarHomePresentation
import dev.caraalink.video.CarVideoEncoder
import dev.caraalink.video.VideoEncoder
import dev.caraalink.video.WeatherProvider
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One connection to the head unit: ADB transport, PXC control, mirror
 * control/data, H.264 encoder and what's on the car screen.
 */
class CarSession(
    private val context: Context,
    private val accessory: UsbAccessory,
    private val phone: PhoneActions,
    private val onEnded: (String) -> Unit,
) : MirrorHost {

    interface PhoneActions {
        fun requestScreenCapture()
        fun back()
    }

    private val logger = DiagnosticLogger(context, "car-session")
    private var transport: AdbTransport? = null
    private var config: CaptureConfig? = null
    private var lastBitRate: Int = -1
    private var encoder: VideoEncoder? = null
    private var pxc: PxcChannel? = null
    private val features = CarFeatures(context)
    private val display = CarDisplay(context, listOf(
        CarHomePresentation.Tile("Android Auto", R.drawable.ic_tile_android_auto, Color.parseColor("#1E8E3E")) { openAndroidAuto() },
        CarHomePresentation.Tile("Phone screen", R.drawable.ic_tile_phone_screen, Color.parseColor("#1A73E8")) { openMirror() },
        CarHomePresentation.Tile("Settings", R.drawable.ic_tile_settings, Color.parseColor("#5F6368")) { showSettings() },
    ), onBack = { phone.back() }, onExit = ::exitToHome)

    private fun exitToHome(): Unit { display.showHome() }


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
        startWeatherUpdates()
        return true
    }

    @Volatile private var weatherThread: Thread? = null

    private fun startWeatherUpdates() {
        weatherThread = Thread({
            try {
                while (!Thread.currentThread().isInterrupted) {
                    WeatherProvider.fetch(context)?.let { display.setInfo(it) }
                    Thread.sleep(15 * 60 * 1000L)
                }
            } catch (_: InterruptedException) {
            }
        }, "CarWeather").also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        transport?.stop("stopped by user") ?: release()
    }

    fun setProjection(projection: MediaProjection) {
        val show = mirrorWanted
        mirrorWanted = false
        display.setProjection(projection, showMirrorNow = show)
        logger.log("Screen capture granted (showMirror=$show)")
    }

    private fun release() {
        aaWatchdog.removeCallbacks(aaWatchdogTick)
        dev.caraalink.aa.AaReconnectBus.onVideoSettingsChanged = null
        dev.caraalink.aa.AaSessionProbe.active?.stop()
        aaDecoder?.stop()
        aaDecoder = null
        weatherThread?.interrupt()
        weatherThread = null
        android.os.Handler(android.os.Looper.getMainLooper()).post { features.stop() }
        encoder?.release()
        encoder = null
        display.release()
        synchronized(frameLock) { dataStream = null; pullQueue.clear() }
    }

    private fun onClientInfo(info: JSONObject) {
        logger.log("Head unit: ${info.optString("HUName")} screenType=${info.optInt("screenType")} " +
            "mirror=${info.optBoolean("supportScreenMirroring")} touch=${info.optBoolean("supportScreenTouch")}")
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
    // Confirmed from a real logcat capture: openAndroidAuto()'s only guard
    // was checking AaSessionProbe.active on the CALLING thread, but the
    // probe itself isn't created until partway through the background
    // Thread below -- a real gap where `active` is still null (old probe
    // just stopped, new one not constructed yet) that TWO calls can both
    // walk through at once (e.g. the watchdog's own restart racing a tap on
    // the AA tile a moment later). Both then ran their own independent
    // accessibility-toggle check concurrently -- one's "server not up, tap
    // Start" stepped on the other's freshly-started session by tapping
    // "Stop" on it instead (the overflow menu read stale "on" state),
    // killing a session that had just barely connected (session failed:
    // Connection reset, immediately after that tap, logged live).
    @Volatile private var openingAa = false
    private val aaServerRecoveryRequested = AtomicBoolean(false)

    private fun openAndroidAuto() {
        if (dev.caraalink.aa.AaSessionProbe.active != null) {
            display.setStatus("AA: already running")
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
        display.setStatus("AA: checking head unit server…")
        logger.log("openAndroidAuto: checking 127.0.0.1:5277 before starting")
        Thread({
            fun serverUp() = isListening(5277)
            var up = serverUp()
            if (!up) {
                if (dev.caraalink.settings.AppSettings.autoStartHeadUnitServer(context)) {
                    // Start is state-aware: it taps only when AA's menu says
                    // the server is off. A failed TCP probe alone must never
                    // trigger a Stop action on an enabled server.
                    logger.log("openAndroidAuto: server not accepting TCP -- requesting safe start")
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        display.setStatus("AA: starting head unit server…")
                        dev.caraalink.accessibility.HeadUnitServerLauncher.start(context)
                    }
                    Thread.sleep(2500)
                    up = serverUp()
                    if (!up) logger.log("openAndroidAuto: server did not accept TCP after start request; continuing without another toggle")
                } else {
                    // "Auto-start head unit server" is off (Settings > Connection) --
                    // the owner wants to flip AA's developer-mode switch themselves,
                    // not have this app drive the accessibility service for them.
                    // Just tell them, same notification the "service not enabled"
                    // path already uses, no accessibility automation triggered.
                    logger.log("openAndroidAuto: server not listening, auto-start disabled -- notifying only")
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        display.setStatus("AA: start the head unit server on your phone")
                        dev.caraalink.accessibility.HeadUnitServerLauncher.notifyOpenAndroidAutoManually(context)
                    }
                }
            }
            // Confirmed from a real logcat capture: exiting AA closes our
            // socket, which kicks off gearhead's OWN teardown of the whole
            // car-projection session (releases automotive projection, tears
            // down its displays, disconnects its car-client connector --
            // that disconnect logged TWICE, ~2.1s apart). The dev-mode
            // socket (127.0.0.1:5277) stays listening the whole time since
            // only the specific session tore down, not the server itself --
            // so reopening right after exit used to skip straight to a new
            // AaSessionProbe with no delay at all, landing while gearhead's
            // teardown was still mid-flight (its window manager was still
            // "tearing down a window that's still in use" a second after
            // our new probe had already started). Give it the same grace
            // window the accessibility-retry path already gets.
            if (up) Thread.sleep(2000)
            logger.log("openAndroidAuto: starting AA session probe")
            val probe = dev.caraalink.aa.AaSessionProbe(context)
            probe.onInitialConnectionFailure = { error ->
                val canRecover = dev.caraalink.settings.AppSettings.autoStartHeadUnitServer(context) &&
                    dev.caraalink.accessibility.HeadUnitServerLauncher.isAccessibilityServiceEnabled(context)
                if (canRecover && aaServerRecoveryRequested.compareAndSet(false, true)) {
                    logger.log("AA startup handshake failed (${error.javaClass.simpleName}) -- requesting one state-aware server recovery")
                    val main = android.os.Handler(android.os.Looper.getMainLooper())
                    main.post { dev.caraalink.accessibility.HeadUnitServerLauncher.recover(context) }
                    // Give accessibility automation time to read the menu,
                    // perform at most one Stop/Start cycle, and let Gearhead
                    // reopen its listener before the next protocol attempt.
                    main.postDelayed({
                        if (display.mode == CarDisplay.Mode.AA && dev.caraalink.aa.AaSessionProbe.active == null) {
                            openAndroidAuto()
                        }
                    }, AA_SERVER_RECOVERY_RETRY_MS)
                    true
                } else false
            }
            probe.start()
            openingAa = false
            android.os.Handler(android.os.Looper.getMainLooper()).post { showAaSurface() }
        }, "AA-server-check").start()
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
    private val aaWatchdogTick = object : Runnable {
        override fun run() {
            val probe = dev.caraalink.aa.AaSessionProbe.active
            if (display.mode == CarDisplay.Mode.AA) {
                if (probe == null) {
                    // The session ended on its own (e.g. the phone's AA app
                    // sent a clean SHUTDOWN_REQUEST -- see AaSessionProbe)
                    // rather than CarSession's own UI ever calling
                    // AaSessionProbe.stop(). Without this the car was left
                    // staring at AA's last frozen frame forever instead of
                    // falling back to CarAALink's own home screen, the way a
                    // real head unit returns to its launcher when AA exits.
                    logger.log("AA watchdog: session ended on its own -- returning to car home")
                    watchdogRestartStreak = 0
                    display.showHome()
                } else {
                    val staleMs = System.currentTimeMillis() - probe.lastVideoFrameAt
                    if (probe.hasReceivedVideo && !probe.isConnectionActive) {
                        // AaSessionProbe owns reconnects after EOF/reset and
                        // deliberately backs off between attempts. Do not
                        // mistake that quiet backoff window for a frozen,
                        // still-connected video stream and cancel the retry.
                        aaWatchdog.postDelayed(this, AA_WATCHDOG_CHECK_MS)
                        return
                    }
                    if (!probe.hasReceivedVideo) {
                        val startupMs = staleMs
                        if (startupMs > AA_WATCHDOG_STARTUP_MS) {
                            logger.log("AA watchdog: no video after ${startupMs}ms startup grace -- restarting session")
                            dev.caraalink.diagnostics.PerfStats.aaReconnects++
                            watchdogRestartStreak++
                            probe.stop()
                            aaWatchdog.postDelayed({ openAndroidAuto() }, AA_TEARDOWN_RESTART_DELAY_MS)
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
                        aaWatchdog.postDelayed({ openAndroidAuto() }, AA_TEARDOWN_RESTART_DELAY_MS)
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
            // Confirmed from a real log: reconnecting immediately after
            // stop() got a healthy TCP connect but then silence -- no
            // VERSION_RESPONSE ever, watchdog restarting every 10s with the
            // same result, needing a full app/server restart to recover.
            // This is a *warm* restart (gearhead had a live session we just
            // yanked away), not the cold-start "service not up yet" case --
            // it likely needs a moment to actually tear down the old
            // session before it's ready to service a new one. A brief
            // grace period before reconnecting, same idea as the existing
            // accessibility-auto-start delay, gives it that room instead of
            // racing it.
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ openAndroidAuto() }, 1500)
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
        // My first attempt at this fix (guarding on AaSessionProbe.active ===
        // myProbe) was wrong -- confirmed wrong by a real log, a fresh probe
        // still died ~2ms after starting, every single restart. The actual
        // mechanism: ANY restart (watchdog, settings change) dismisses the
        // OLD AaCarPresentation as part of building a new one, even though
        // we're staying in AA mode the whole time. That old presentation's
        // surfaceDestroyed teardown is indistinguishable from "user left AA
        // for Home/Mirror" by probe identity alone -- active already points
        // at the brand-new probe by the time it fires, so the old guard was
        // always true. The real signal for "did the user actually leave AA"
        // is display.mode: showHome()/showMirror() set it away from AA
        // *before* dismissing this presentation, while an in-place restart
        // sets it to AA first and only then dismisses -- so checking it here
        // tells the two cases apart correctly.
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
                    // Leaving the AA tile (Home/Mirror picked instead) used to
                    // just detach the video sink, leaving AaSessionProbe's TLS
                    // connection to the phone's own head-unit-server mode
                    // sitting open in the background indefinitely. Actually
                    // end the session so the phone disconnects for real --
                    // but only when we really left AA mode, not when this is
                    // just the previous presentation's own teardown mid-restart.
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

    @Volatile private var mirrorWanted = false

    private fun openMirror() {
        if (display.showMirror()) return
        mirrorWanted = true
        askForCapture()
    }

    private fun askForCapture() {
        display.setStatus("Tap \"Start now\" on your phone to allow screen mirroring")
        phone.requestScreenCapture()
    }

    private fun showSettings() {
        val c = config
        display.setStatus(
            if (c == null) "No video config yet"
            else "Car ${c.width}x${c.height}, ${if (c.wantFps == 0) "on demand" else "${c.wantFps} fps"}, " +
                "H.264 ${(if (c.bitRate > 0) c.bitRate else DEFAULT_BITRATE) / 1000} kbps, frames sent $framesSent"
        )
    }

    // --- MirrorHost ---

    override fun onCaptureConfig(config: CaptureConfig) {
        val previous = this.config
        this.config = config
        CarScreenInfo.update(config.width, config.height)
        // Measured from a real log (2026-11-06): in pull mode the car issues
        // "frame request" calls at ~75-79/s, but this was hardcoded to a
        // 30fps encode target -- requests=9899, sent=9899, encoded=4373 over
        // the same ~134s session, meaning well over half of every frame
        // actually sent to the car was a starvation-fallback *repeat* of
        // already-sent data, not fresh content. That's the measured
        // mechanism behind "TV lag / repeats motion": the car is pulling far
        // faster than we were producing anything new to give it. Raising the
        // pull-mode target toward the car's own observed pull cadence so
        // more pulls get genuinely fresh frames instead of a stale resend.
        val fps = if (config.wantFps > 0) config.wantFps else 60
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
        val enc: VideoEncoder = CarVideoEncoder(config.encodeWidth, config.encodeHeight, fps, bitRate, ::onEncodedFrame)
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
        display.setStatus("Connected")
        // Settings > Android Auto > "Jump straight to Android Auto" had a
        // getter (AppSettings.aaAutoLaunch) but nothing ever called it --
        // confirmed live, the toggle did nothing. This is the first point in
        // a session where the car display actually exists to switch away
        // from; only do it once per session (display.attach() re-runs this
        // on every real CONFIG_CAPTURE, not just the first).
        if (!autoLaunchedAa && dev.caraalink.settings.AppSettings.aaAutoLaunch(context)) {
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
        if (key == MirrorControlChannel.KEY_HOME || key == MirrorControlChannel.KEY_BACK) {
            display.showHome()
            display.setStatus("Connected")
        }
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
            discardingUntilKeyframe = false
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

    // Set whenever a non-key frame gets dropped instead of forwarded (queue/
    // backlog overflow). MediaCodec's internal reference chain still treats
    // that dropped frame as the previous picture, so every later P-frame we
    // *do* forward implicitly depends on a picture the car's decoder never
    // received -- that mismatch is corrupted macroblocks ("TV glitches")
    // until the next IDR. Confirmed real (real-headunit evidence review,
    // 2026-11-06): the old code only re-checked queue fullness on the next
    // frame, so as soon as the car drained one slot below MAX_PULL_QUEUE a
    // fresh non-key frame got enqueued again even though no keyframe had
    // landed yet, re-introducing the same corruption. This flag keeps
    // dropping every non-key frame until an actual keyframe arrives,
    // regardless of queue occupancy in between.
    private var discardingUntilKeyframe = false

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
            if (isKey) {
                lastKeyFrame = data
                discardingUntilKeyframe = false
            } else if (discardingUntilKeyframe) {
                return
            }
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
                    if (!isKey) {
                        // Drop the whole stale backlog, not just this frame --
                        // letting the car drain MAX_PULL_QUEUE buffered-but-old
                        // frames just replays old motion instead of catching
                        // up, which is the "repeats motion" symptom.
                        dev.caraalink.diagnostics.PerfStats.mirrorFramesDropped += pullQueue.size
                        pullQueue.clear()
                        discardingUntilKeyframe = true
                        encoder?.requestKeyFrame()
                        return
                    }
                    dev.caraalink.diagnostics.PerfStats.mirrorFramesDropped += pullQueue.size
                    pullQueue.clear()
                }
                pullQueue.add(data)
                return
            }
            if (stream.backlogBytes() > MAX_PUSH_BACKLOG) {
                if (!isKey) {
                    stream.clearBacklog()
                    discardingUntilKeyframe = true
                    encoder?.requestKeyFrame()
                    return
                }
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
        private const val AA_TEARDOWN_RESTART_DELAY_MS = 1_500L
        private const val AA_SERVER_RECOVERY_RETRY_MS = 7_000L

        // AA's head unit server never answers again after a connection we close, so probe by bind, not connect.
        internal fun isListening(port: Int): Boolean = runCatching {
            java.net.ServerSocket(port, 1, java.net.InetAddress.getByName("127.0.0.1")).close()
            false
        }.getOrElse { it is java.net.BindException }
    }
}
