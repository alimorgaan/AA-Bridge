package dev.caraalink.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import dev.caraalink.link.CarSession
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import dev.caraalink.MainActivity
import dev.caraalink.connection.ConnectionPhase
import dev.caraalink.connection.ConnectionSnapshot
import dev.caraalink.connection.ConnectionStateHolder
import dev.caraalink.R
import dev.caraalink.settings.AppSettings
import dev.caraalink.connection.CarLink
import dev.caraalink.connection.WirelessPhase
import dev.caraalink.diagnostics.AdbHandshakeProbe
import dev.caraalink.diagnostics.DiagnosticLogger
import dev.caraalink.link.SocketTransport
import dev.caraalink.wireless.CarHello
import dev.caraalink.wireless.CarMatch
import dev.caraalink.wireless.NearbyDevice
import dev.caraalink.wireless.RememberedCar
import dev.caraalink.wireless.WirelessLink
import dev.caraalink.wireless.findCar
import java.net.InetAddress
import dev.caraalink.diagnostics.UsbSnifferDiagnostic

/**
 * Keeps the USB link alive while the app is backgrounded (screen off is
 * expected and fine — see claude/roadmap.md; this does NOT force the screen
 * on, only holds a partial wake lock so the CPU doesn't doze mid-read).
 * Currently only drives [UsbSnifferDiagnostic]; swap in the real protocol
 * once Phase 3 lands.
 */
class UsbLinkForegroundService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var sniffer: UsbSnifferDiagnostic? = null
    private var adbProbe: AdbHandshakeProbe? = null
    private var session: CarSession? = null
    private var pendingAccessory: UsbAccessory? = null
    private var resumeActiveMode: ((UsbAccessory) -> Unit)? = null
    private var detachStopRunnable: Runnable? = null
    // Confirmed from real logs (chatgpt-status.md "EIO" findings): the
    // Mirror transport dies with "read failed: EIO" after sustained use,
    // at a different request count every time -- not a fixed timeout or
    // counter, looks like CarbitLink itself dropping the link. Previously
    // this always fell straight to stopSelfCleanly(), which is the actual
    // mechanism behind the long-standing "have to manually toggle head
    // unit server" complaint. The accessory is usually still physically
    // attached when this happens (unlike a real unplug, which the
    // detachReceiver below already handles separately with its own grace
    // period) -- reusing the already-granted [pendingAccessory] avoids
    // re-triggering Android's accessory chooser (see maybeScheduleReconnect's
    // comment for why a *fresh* openAccessory() retry loop was avoided
    // before). Capped and backed off so a permanently-dead link still gives
    // up instead of retrying forever.
    private var transportReconnectAttempts = 0
    private var transportReconnectRunnable: Runnable? = null
    private var stopping = false

    private val wirelessLog by lazy { DiagnosticLogger(this, "wireless") }
    private var wirelessLink: WirelessLink? = null
    private var wirelessTarget: RememberedCar? = null
    private var connectingTo: String? = null
    private var carHost: Pair<String, InetAddress?>? = null
    private var wirelessStreamed = false
    private var wirelessTimeout: Runnable? = null
    private var reconnectUntil = 0L

    private val stateListener: (ConnectionSnapshot) -> Unit = {
        updateNotification(it)
        // A reconnect that streams again refills the budget; a dead link never streams, so it still gives up.
        if (it.phase == ConnectionPhase.STREAMING && it.link == CarLink.WIRELESS && it.wireless != WirelessPhase.IDLE) {
            handler.post { onWirelessStreaming() }
        }
        if (it.phase == ConnectionPhase.STREAMING) {
            handler.post { transportReconnectAttempts = 0 }
            AppSettings.setCarConnectedOnce(this)
        }
    }

    // Shouldn't keep running (and showing a notification) indefinitely once
    // actually unplugged from the car -- 30s grace in case it's a brief
    // reseat, then quietly stop: no lingering service, no lingering AA
    // reconnect loop, notification gone.
    private val detachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.i(TAG, "USB_ACCESSORY_DETACHED -- stopping in 30s unless reattached")
            detachStopRunnable?.let { handler.removeCallbacks(it) }
            val runnable = Runnable {
                Log.i(TAG, "detachStopRunnable: still unplugged after 30s, stopping")
                stopping = true
                stopSelfCleanly()
            }
            detachStopRunnable = runnable
            handler.postDelayed(runnable, 30_000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        ConnectionStateHolder.addListener(stateListener)
        val filter = IntentFilter(UsbManager.ACTION_USB_ACCESSORY_DETACHED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(detachReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(detachReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand: action=${intent?.action}")
        when (intent?.action) {
            ACTION_START_SNIFFER -> handleStart(intent) { accessory -> beginSniffing(accessory) }
            ACTION_START_ADB_PROBE -> handleStart(intent) { accessory -> beginAdbProbe(accessory) }
            ACTION_START_SESSION -> handleStart(intent) { accessory -> beginSession(accessory) }
            ACTION_START_WIRELESS -> {
                val address = intent.getStringExtra(EXTRA_CAR_ADDRESS)
                val target = address?.let { RememberedCar(intent.getStringExtra(EXTRA_CAR_NAME) ?: it, it) }
                    ?: AppSettings.wirelessCar(this)
                beginWireless(target)
            }
            ACTION_STOP -> {
                Log.i(TAG, "onStartCommand: ACTION_STOP")
                stopping = true
                stopSelfCleanly()
            }
        }
        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent, begin: (UsbAccessory) -> Unit) {
        val accessory = intent.getParcelableExtra<UsbAccessory>(UsbManager.EXTRA_ACCESSORY)
        if (accessory == null) {
            Log.w(TAG, "handleStart: ${intent.action} with no EXTRA_ACCESSORY")
            return
        }
        Log.i(TAG, "handleStart: ${intent.action} with accessory=${accessory.manufacturer}/${accessory.model}")
        pendingAccessory = accessory
        // The cable wins: drop any wireless search before the cable session starts.
        closeWireless(disconnect = true)
        detachStopRunnable?.let { handler.removeCallbacks(it) }
        detachStopRunnable = null
        // A fresh connect request from outside (user action, accessory
        // attach) -- not an internal transport-failure retry -- so the
        // backoff/stop state from any earlier reconnect attempt no longer
        // applies.
        transportReconnectRunnable?.let { handler.removeCallbacks(it) }
        transportReconnectRunnable = null
        transportReconnectAttempts = 0
        stopping = false
        // CRASH SEEN ON REAL HARDWARE (claude/roadmap.md): starting a
        // connectedDevice-typed foreground service throws SecurityException
        // once the accessory's permission grant has lapsed (e.g. it was
        // unplugged between the intent being sent and this running). Don't
        // let that take the whole app down.
        val started = try {
            startForegroundWithMic()
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "startForeground(connectedDevice) refused -- accessory permission likely lapsed", e)
            ConnectionStateHolder.update { it.copy(phase = ConnectionPhase.ERROR, lastError = "No USB accessory permission") }
            stopSelf()
            false
        }
        if (started) {
            acquireWakeLock()
            begin(accessory)
        }
    }

    private fun beginSniffing(accessory: UsbAccessory) {
        Log.i(TAG, "beginSniffing: ${accessory.manufacturer}/${accessory.model}")
        adbProbe?.stop()
        sniffer?.stop()
        val s = UsbSnifferDiagnostic(this)
        sniffer = s
        resumeActiveMode = ::beginSniffing
        s.start(accessory)
    }

    private fun beginAdbProbe(accessory: UsbAccessory) {
        Log.i(TAG, "beginAdbProbe: ${accessory.manufacturer}/${accessory.model}")
        sniffer?.stop()
        adbProbe?.stop()
        val p = AdbHandshakeProbe(this)
        adbProbe = p
        resumeActiveMode = ::beginAdbProbe
        p.start(accessory)
    }

    private fun beginSession(accessory: UsbAccessory) {
        // Stop a wireless session while link is still WIRELESS, so its ERROR can't stop the service.
        // It keeps Android Auto running, and the cable session attaches to it (openAndroidAuto).
        if (ConnectionStateHolder.snapshot.link == CarLink.WIRELESS) {
            session?.stop()
            session = null
        }
        ConnectionStateHolder.update { it.copy(link = CarLink.CABLE, wireless = WirelessPhase.IDLE, suggestion = null) }
        Log.i(TAG, "beginSession: ${accessory.manufacturer}/${accessory.model}")
        sniffer?.stop()
        adbProbe?.stop()
        session?.stop()
        val s = CarSession(this, accessory) { reason ->
            handler.post {
                Log.i(TAG, "session ended: $reason")
                session = null
                endCableSession(retryable = isTransportIoFailure(reason))
            }
        }
        session = s
        if (s.start()) return
        session = null
        ConnectionStateHolder.update { it.copy(phase = ConnectionPhase.ERROR, lastError = "accessory not openable") }
        // Right after a replug the accessory can take a moment to open, so this is a retryable link failure too.
        endCableSession(retryable = true)
    }

    /** A cable session that failed on the link gets the reconnect budget; anything else stops the service. */
    private fun endCableSession(retryable: Boolean) {
        if (retryable && !stopping && pendingAccessory != null) scheduleTransportReconnect() else stopSelfCleanly()
    }

    override fun onDestroy() {
        closeWireless(disconnect = true)
        session?.stop()
        session = null
        ConnectionStateHolder.removeListener(stateListener)
        detachStopRunnable?.let { handler.removeCallbacks(it) }
        detachStopRunnable = null
        runCatching { unregisterReceiver(detachReceiver) }
        sniffer?.stop()
        adbProbe?.stop()
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Called by the sniffer/transport layer (via ConnectionStateHolder) on
     * link loss. No auto-retry here (holding/retrying the accessory blocked
     * Android's app chooser on the next plug-in) -- just release everything;
     * [detachReceiver] handles the real "give it 30s in case it's a brief
     * reseat" grace period off the actual USB detach event instead.
     */
    private var lastPhase: ConnectionPhase? = null

    private fun maybeScheduleReconnect(snapshot: ConnectionSnapshot) {
        val previous = lastPhase
        lastPhase = snapshot.phase
        // Wireless runs its own reconnect window; see onWirelessSessionEnded.
        if (!shouldStopOnError(previous, snapshot)) return
        stopSelfCleanly()
    }

    /**
     * Android 14 refuses a microphone-typed start from the background (transport reconnects run there),
     * so fall back to connectedDevice alone rather than failing the session.
     */
    private fun startForegroundWithMic(wireless: Boolean = false) {
        val notification = buildNotification(ConnectionStateHolder.snapshot)
        val micGranted = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        // Wi-Fi Direct scans count as location access up to Android 12, so a reconnect mid-drive needs this type.
        val location = if (wireless && needsLocationForWifiDirect() && hasFineLocation()) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        // The microphone type only exists from Android 11; on 10 it's an undeclared type and throws.
        if (micGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or location)
                return
            } catch (e: SecurityException) {
                Log.w(TAG, "startForeground with microphone refused -- retrying connectedDevice only", e)
            }
        }
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or location)
    }

    private fun needsLocationForWifiDirect() = Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2

    private fun hasFineLocation() =
        checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED

    // --- wireless ---

    private fun beginWireless(target: RememberedCar?) {
        // startForegroundService needs startForeground even when there turns out to be nothing to do.
        startForegroundWithMic(wireless = true)
        if (target == null || ConnectionStateHolder.snapshot.link == CarLink.CABLE) {
            wirelessLog.log("start wireless ignored: ${if (target == null) "no car" else "cable session active"}")
            if (session == null) stopSelfCleanly()
            return
        }
        wirelessLog.log("start wireless: looking for '${target.name}' ${target.address}")
        stopping = false
        acquireWakeLock()
        closeWireless(disconnect = false)
        wirelessTarget = target
        wirelessStreamed = false
        ConnectionStateHolder.update {
            it.copy(link = CarLink.WIRELESS, wireless = WirelessPhase.SEARCHING, suggestion = null,
                phase = ConnectionPhase.DISCONNECTED, target = target)
        }
        startWirelessSession()
        wirelessLink = WirelessLink(this, wirelessLog, wirelessListener).also { it.scan() }
        armWirelessTimeout(SEARCH_TIMEOUT_MS) { endWireless(WirelessPhase.NOT_FOUND) }
    }

    private fun startWirelessSession() {
        session?.stop()
        session = null
        lateinit var s: CarSession
        s = CarSession(this, null, keepAndroidAuto = true) { reason -> handler.post { if (session === s) onWirelessSessionEnded(reason) } }
        session = s
        s.startWireless()
    }

    private val wirelessListener = object : WirelessLink.Listener {
        override fun onPeers(peers: List<NearbyDevice>) {
            val target = wirelessTarget ?: return
            if (connectingTo != null || carHost != null) return
            when (val match = findCar(peers, target)) {
                is CarMatch.Found -> {
                    connectingTo = match.device.address
                    val found = target.copy(name = match.device.name)
                    wirelessTarget = found
                    val reconnecting = reconnectUntil > 0
                    ConnectionStateHolder.update {
                        it.copy(wireless = if (reconnecting) WirelessPhase.RECONNECTING else WirelessPhase.CONNECTING, suggestion = null, target = found)
                    }
                    wirelessLink?.connect(match.device.address)
                    if (!reconnecting) armWirelessTimeout(CONNECT_TIMEOUT_MS) { onAttemptFailed() }
                }
                is CarMatch.SameName -> ConnectionStateHolder.update { it.copy(suggestion = match.device) }
                CarMatch.None -> {}
            }
        }

        override fun onCarReachable(host: String, local: InetAddress?) {
            carHost = host to local
            if (ConnectionStateHolder.snapshot.wireless == WirelessPhase.SEARCHING) {
                ConnectionStateHolder.update { it.copy(wireless = WirelessPhase.CONNECTING) }
                armWirelessTimeout(CONNECT_TIMEOUT_MS) { onAttemptFailed() }
            }
            sendHello(host, local)
        }

        override fun onGroupLost() {
            carHost = null
            connectingTo = null
            if (stopping || wirelessTarget == null) return
            if (!wirelessStreamed) {
                wirelessLink?.scan()
                return
            }
            if (needsLocationForWifiDirect() && !hasFineLocation()) {
                notifyOpenToReconnect()
                endWireless(WirelessPhase.IDLE)
                return
            }
            wirelessLog.log("link lost -- reconnecting for up to ${RECONNECT_WINDOW_MS / 1000}s")
            reconnectUntil = android.os.SystemClock.elapsedRealtime() + RECONNECT_WINDOW_MS
            ConnectionStateHolder.update { it.copy(wireless = WirelessPhase.RECONNECTING) }
            startWirelessSession()
            wirelessLink?.scan()
            armWirelessTimeout(RECONNECT_WINDOW_MS) { endWireless(WirelessPhase.IDLE) }
        }

        override fun onWifiOff() = endWireless(WirelessPhase.WIFI_OFF)
    }

    /** Our own name first; CarbitLink's only if the car hasn't connected back within 3 s. */
    private fun sendHello(host: String, local: InetAddress?) {
        Thread({
            val before = SocketTransport.accepted
            for ((label, pkg) in listOf("own" to packageName, "carbitlink" to CarHello.CARBITLINK_PACKAGE)) {
                val answered = CarHello.send(host, local, pkg)
                val until = android.os.SystemClock.elapsedRealtime() + HELLO_WAIT_MS
                while (SocketTransport.accepted == before && android.os.SystemClock.elapsedRealtime() < until) Thread.sleep(100)
                if (SocketTransport.accepted > before) {
                    wirelessLog.log("hello answered: $label (reply=$answered)")
                    return@Thread
                }
                wirelessLog.log("hello as $label: no connect-back (reply=$answered)")
            }
            handler.post { if (carHost?.first == host) onAttemptFailed() }
        }, "car-hello").start()
    }

    /** Gives up, or inside the reconnect window drops the attempt and scans again. */
    private fun onAttemptFailed() {
        val phase = afterFailedAttempt(reconnectUntil, android.os.SystemClock.elapsedRealtime())
        if (phase != null) {
            endWireless(phase)
            return
        }
        wirelessLog.log("attempt failed -- scanning again inside the reconnect window")
        connectingTo = null
        carHost = null
        wirelessLink?.disconnect()
        wirelessLink?.scan()
    }

    private fun onWirelessStreaming() {
        val target = wirelessTarget ?: return
        cancelWirelessTimeout()
        reconnectUntil = 0L
        wirelessStreamed = true
        AppSettings.setWirelessCar(this, target)
        ConnectionStateHolder.update { it.copy(wireless = WirelessPhase.IDLE, suggestion = null) }
        wirelessLog.log("streaming to '${target.name}' -- remembered")
    }

    /** The car closed its connections; a group that is still up gets a fresh session and one more hello. */
    private fun onWirelessSessionEnded(reason: String) {
        session = null
        if (stopping || wirelessLink == null) return
        wirelessLog.log("wireless session ended: $reason")
        val host = carHost ?: return
        startWirelessSession()
        sendHello(host.first, host.second)
    }

    private fun endWireless(phase: WirelessPhase) {
        wirelessLog.log("wireless ended: $phase")
        ConnectionStateHolder.update { it.copy(wireless = phase) }
        stopping = true
        stopSelfCleanly()
    }

    private fun closeWireless(disconnect: Boolean) {
        cancelWirelessTimeout()
        wirelessLink?.let {
            if (disconnect) it.disconnect()
            it.close()
        }
        wirelessLink = null
        wirelessTarget = null
        connectingTo = null
        carHost = null
        reconnectUntil = 0L
    }

    private fun armWirelessTimeout(ms: Long, onTimeout: () -> Unit) {
        cancelWirelessTimeout()
        val r = Runnable { wirelessTimeout = null; onTimeout() }
        wirelessTimeout = r
        handler.postDelayed(r, ms)
    }

    private fun cancelWirelessTimeout() {
        wirelessTimeout?.let { handler.removeCallbacks(it) }
        wirelessTimeout = null
    }

    private fun notifyOpenToReconnect() {
        val name = wirelessTarget?.name ?: return
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_bridge)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.wireless_open_to_reconnect, name))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { getSystemService(NotificationManager::class.java).notify(OPEN_TO_RECONNECT_ID, n) }
    }

    /** Transport-level read/write failure, as opposed to a clean close or a real unplug. */
    private fun isTransportIoFailure(reason: String): Boolean =
        reason.contains("read failed", ignoreCase = true) || reason.contains("write failed", ignoreCase = true)

    private fun scheduleTransportReconnect() {
        val accessory = pendingAccessory ?: return
        if (transportReconnectAttempts >= MAX_TRANSPORT_RECONNECT_ATTEMPTS) {
            Log.w(TAG, "scheduleTransportReconnect: giving up after $transportReconnectAttempts attempts")
            stopSelfCleanly()
            return
        }
        transportReconnectAttempts++
        val delayMs = TRANSPORT_RECONNECT_BASE_DELAY_MS * (1L shl (transportReconnectAttempts - 1))
        Log.i(TAG, "scheduleTransportReconnect: attempt $transportReconnectAttempts in ${delayMs}ms")
        val runnable = Runnable {
            transportReconnectRunnable = null
            if (!stopping) beginSession(accessory)
        }
        transportReconnectRunnable = runnable
        handler.postDelayed(runnable, delayMs)
    }

    private var cleaningUp = false

    private fun stopSelfCleanly() {
        // A session stopped in here reports ERROR, which reaches maybeScheduleReconnect and calls back in.
        if (cleaningUp) return
        cleaningUp = true
        try {
            cleanUp()
        } finally {
            cleaningUp = false
        }
    }

    private fun cleanUp() {
        transportReconnectRunnable?.let { handler.removeCallbacks(it) }
        transportReconnectRunnable = null
        closeWireless(disconnect = true)
        session?.stop()
        session = null
        // Wireless sessions leave Android Auto running for a handover; the service ending is the last owner.
        dev.caraalink.aa.AaSessionProbe.active?.stop()
        // Searching phases end with the service; NOT_FOUND, FAILED and WIFI_OFF stay for Home.
        ConnectionStateHolder.update {
            val active = it.wireless == WirelessPhase.SEARCHING || it.wireless == WirelessPhase.CONNECTING || it.wireless == WirelessPhase.RECONNECTING
            it.copy(link = CarLink.NONE, wireless = if (active) WirelessPhase.IDLE else it.wireless)
        }
        sniffer?.stop()
        adbProbe?.stop()
        adbProbe = null
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "caraalink:usb-link").apply {
            setReferenceCounted(false)
            acquire(/* no timeout — released explicitly on stop/destroy */)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.fgs_channel), NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun updateNotification(snapshot: ConnectionSnapshot) {
        maybeScheduleReconnect(snapshot)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(snapshot))
    }

    private fun buildNotification(snapshot: ConnectionSnapshot): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, UsbLinkForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        // Drivers see this on every drive, so plain words; the raw error stays in the logs.
        val carName = wirelessTarget?.name ?: snapshot.carName ?: ""
        val statusText = when {
            snapshot.link == CarLink.WIRELESS && snapshot.wireless == WirelessPhase.RECONNECTING ->
                getString(R.string.fgs_reconnecting_wireless, carName)
            snapshot.link == CarLink.WIRELESS && snapshot.phase == ConnectionPhase.STREAMING ->
                getString(R.string.fgs_connected_wireless, carName)
            else -> cableStatus(snapshot)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_bridge)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(statusText)
            .setOngoing(true)
            .setContentIntent(openAppIntent)
            .addAction(0, getString(R.string.fgs_stop), stopIntent)
            .build()
    }

    private fun cableStatus(snapshot: ConnectionSnapshot): String = when (snapshot.phase) {
        ConnectionPhase.STREAMING -> getString(R.string.fgs_connected)
        ConnectionPhase.ERROR -> getString(R.string.fgs_problem)
        ConnectionPhase.SNIFFING -> "Diagnostic sniffer running (${snapshot.bytesRead} bytes)"
        else -> getString(R.string.fgs_connecting)
    }

    companion object {
        private const val TAG = "UsbLinkForegroundService"
        private const val CHANNEL_ID = "usb_link"
        private const val NOTIFICATION_ID = 1
        private const val MAX_TRANSPORT_RECONNECT_ATTEMPTS = 4
        private const val TRANSPORT_RECONNECT_BASE_DELAY_MS = 2_000L
        private const val OPEN_TO_RECONNECT_ID = 4
        private const val SEARCH_TIMEOUT_MS = 30_000L
        private const val CONNECT_TIMEOUT_MS = 30_000L
        private const val RECONNECT_WINDOW_MS = 120_000L
        private const val HELLO_WAIT_MS = 3_000L

        const val ACTION_START_SNIFFER = "dev.caraalink.service.action.START_SNIFFER"
        const val ACTION_START_ADB_PROBE = "dev.caraalink.service.action.START_ADB_PROBE"
        const val ACTION_STOP = "dev.caraalink.service.action.STOP"
        const val ACTION_START_SESSION = "dev.caraalink.service.action.START_SESSION"
        /** A session that just failed stops the service; a leftover ERROR seen by a later update must not. */
        internal fun shouldStopOnError(previous: ConnectionPhase?, snapshot: ConnectionSnapshot): Boolean =
            snapshot.phase == ConnectionPhase.ERROR && previous != ConnectionPhase.ERROR && snapshot.link != CarLink.WIRELESS

        /** Null keeps trying: inside the mid-drive window a failed attempt goes back to scanning. */
        internal fun afterFailedAttempt(reconnectUntil: Long, now: Long): WirelessPhase? =
            if (now < reconnectUntil) null else WirelessPhase.FAILED

        const val ACTION_START_WIRELESS = "dev.caraalink.service.action.START_WIRELESS"
        const val EXTRA_CAR_ADDRESS = "car_address"
        const val EXTRA_CAR_NAME = "car_name"
    }
}
