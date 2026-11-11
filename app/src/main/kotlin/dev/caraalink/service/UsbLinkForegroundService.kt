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
import dev.caraalink.settings.AppSettings
import dev.caraalink.diagnostics.AdbHandshakeProbe
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

    private val stateListener: (ConnectionSnapshot) -> Unit = {
        updateNotification(it)
        // A reconnect that streams again refills the budget; a dead link never streams, so it still gives up.
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
        Log.i(TAG, "beginSession: ${accessory.manufacturer}/${accessory.model}")
        sniffer?.stop()
        adbProbe?.stop()
        session?.stop()
        val s = CarSession(this, accessory) { reason ->
            handler.post {
                Log.i(TAG, "session ended: $reason")
                session = null
                if (!stopping && isTransportIoFailure(reason) && pendingAccessory != null) {
                    scheduleTransportReconnect()
                } else {
                    stopSelfCleanly()
                }
            }
        }
        session = s
        if (!s.start()) {
            session = null
            ConnectionStateHolder.update { it.copy(phase = ConnectionPhase.ERROR, lastError = "openAccessory() returned null") }
            // openAccessory() can fail this way for a beat right after a
            // physical USB-C reconnect (uevent port0-partner "add" lands a
            // few hundred ms before the accessory is actually openable) --
            // confirmed live: a reconnect attempt landed here mid-retry
            // sequence and this unconditionally called stopSelfCleanly(),
            // throwing away the remaining retry budget even though the
            // accessory physically came back ~4s later. Route through the
            // same retry path as a transport read/write failure instead of
            // giving up on the first miss.
            if (!stopping && pendingAccessory != null) {
                scheduleTransportReconnect()
            } else {
                stopSelfCleanly()
            }
            return
        }
    }

    override fun onDestroy() {
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

    /**
     * There was never a "Disconnect"/"Exit" control anywhere in the app --
     * the only user-facing way to end a session was unplugging the USB
     * cable (which the detach receiver above deliberately waits 30s on, in
     * case it's a brief reseat). Swiping CarAALink away from recents does
     * NOT call onDestroy() on its own -- without overriding this, the
     * foreground service (and the whole USB/AA session underneath it) just
     * keeps running invisibly in the background. That's "CarAALink won't
     * close cleanly": swiping it away looks like closing it, but the stale
     * session stays alive and wedged, which is exactly the kind of state
     * that then needs the head-unit-server toggle to actually break.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "onTaskRemoved: app swiped away -- stopping session")
        stopping = true
        stopSelfCleanly()
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Called by the sniffer/transport layer (via ConnectionStateHolder) on
     * link loss. No auto-retry here (holding/retrying the accessory blocked
     * Android's app chooser on the next plug-in) -- just release everything;
     * [detachReceiver] handles the real "give it 30s in case it's a brief
     * reseat" grace period off the actual USB detach event instead.
     */
    private fun maybeScheduleReconnect(snapshot: ConnectionSnapshot) {
        if (snapshot.phase != ConnectionPhase.ERROR) return
        stopSelfCleanly()
    }

    /**
     * Android 14 refuses a microphone-typed start from the background (transport reconnects run there),
     * so fall back to connectedDevice alone rather than failing the session.
     */
    private fun startForegroundWithMic() {
        val notification = buildNotification(ConnectionStateHolder.snapshot)
        val micGranted = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        // The microphone type only exists from Android 11; on 10 it's an undeclared type and throws.
        if (micGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                return
            } catch (e: SecurityException) {
                Log.w(TAG, "startForeground with microphone refused -- retrying connectedDevice only", e)
            }
        }
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
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

    private fun stopSelfCleanly() {
        transportReconnectRunnable?.let { handler.removeCallbacks(it) }
        transportReconnectRunnable = null
        session?.stop()
        session = null
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
        val channel = NotificationChannel(CHANNEL_ID, "USB link", NotificationManager.IMPORTANCE_LOW)
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

        val statusText = when (snapshot.phase) {
            ConnectionPhase.DISCONNECTED -> "Disconnected"
            ConnectionPhase.ATTACHED -> "USB attached"
            ConnectionPhase.SNIFFING -> "Diagnostic sniffer running (${snapshot.bytesRead} bytes)"
            ConnectionPhase.STREAMING -> "Streaming"
            ConnectionPhase.RECONNECTING -> "Reconnecting (attempt ${snapshot.reconnectAttempts})…"
            ConnectionPhase.ERROR -> "Error: ${snapshot.lastError ?: "unknown"}"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("AA Bridge")
            .setContentText(statusText)
            .setOngoing(true)
            .setContentIntent(openAppIntent)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    companion object {
        private const val TAG = "UsbLinkForegroundService"
        private const val CHANNEL_ID = "usb_link"
        private const val NOTIFICATION_ID = 1
        private const val MAX_TRANSPORT_RECONNECT_ATTEMPTS = 4
        private const val TRANSPORT_RECONNECT_BASE_DELAY_MS = 2_000L

        const val ACTION_START_SNIFFER = "dev.caraalink.service.action.START_SNIFFER"
        const val ACTION_START_ADB_PROBE = "dev.caraalink.service.action.START_ADB_PROBE"
        const val ACTION_STOP = "dev.caraalink.service.action.STOP"
        const val ACTION_START_SESSION = "dev.caraalink.service.action.START_SESSION"
    }
}
