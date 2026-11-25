package dev.caraalink

import android.content.Intent
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import dev.caraalink.aa.AaSessionProbe
import dev.caraalink.aa.AaTlsReceiver
import dev.caraalink.aa.AaVersionProbe
import dev.caraalink.service.UsbLinkForegroundService
import dev.caraalink.settings.AppSettings
import dev.caraalink.ui.AboutScreen
import dev.caraalink.ui.HelpScreen
import dev.caraalink.ui.HomeScreen
import dev.caraalink.ui.NearbyCarsScreen
import dev.caraalink.ui.SettingsScreen
import dev.caraalink.ui.SetupScreen
import dev.caraalink.ui.SupportDialog
import dev.caraalink.support.SupportAsk
import dev.caraalink.ui.theme.AaBridgeTheme

enum class Screen { SETUP, HOME, SETTINGS, HELP, ABOUT, NEARBY }

/** The phone app: one Compose activity; also where Android sends the car's USB attach. */
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private var supportBanner by mutableStateOf(false)
    private var supportPopup by mutableStateOf(false)
    // Set where the intent arrives (onCreate/onNewIntent), read in onResume: onStart runs before onNewIntent.
    private var carLaunchPending = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app is always dark, so the system bar icons stay light even in the phone's light mode.
        enableEdgeToEdge(
            androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        Log.i(TAG, "onCreate: launch intent action=${intent?.action} extras=${intent?.extras}")
        dev.caraalink.diagnostics.CrashMarker.consumeLastCrash(this)?.let { summary ->
            Toast.makeText(this, "Restarted after a crash: $summary", Toast.LENGTH_LONG).show()
        }
        dev.caraalink.update.UpdateChecker.checkIfDue(this)
        maybeAutoStartOnAttach(intent, recreated = savedInstanceState != null)
        maybeStartAaTls(intent)
        carLaunchPending = launchedByCar(intent)
        val firstScreen = if (AppSettings.setupComplete(this)) Screen.HOME else Screen.SETUP
        setContent {
            AaBridgeTheme {
                var screen by rememberSaveable { mutableStateOf(firstScreen) }
                var fromSettings by rememberSaveable { mutableStateOf(false) }
                var nearbyFrom by rememberSaveable { mutableStateOf(Screen.HOME) }
                val back = {
                    screen = when {
                        screen == Screen.NEARBY -> nearbyFrom
                        fromSettings && (screen == Screen.HELP || screen == Screen.ABOUT) -> Screen.SETTINGS
                        else -> Screen.HOME
                    }
                    if (screen == Screen.HOME) fromSettings = false
                }
                val openNearby = { from: Screen -> nearbyFrom = from; screen = Screen.NEARBY }
                BackHandler(enabled = screen != Screen.HOME && screen != Screen.SETUP) { back() }
                when (screen) {
                    Screen.SETUP -> SetupScreen(onDone = { screen = Screen.HOME }, onOpenNearby = { openNearby(Screen.SETUP) })
                    Screen.HOME -> HomeScreen(
                        onOpenSettings = { screen = Screen.SETTINGS },
                        onOpenNearby = { openNearby(Screen.HOME) },
                        showSupportBanner = supportBanner,
                        onOpenSupport = { supportPopup = true },
                    )
                    Screen.SETTINGS -> SettingsScreen(
                        onBack = back,
                        onHelp = { fromSettings = true; screen = Screen.HELP },
                        onAbout = { fromSettings = true; screen = Screen.ABOUT },
                        onSupport = { supportPopup = true },
                    )
                    Screen.HELP -> HelpScreen(onBack = back, onRunSetup = { fromSettings = false; screen = Screen.SETUP })
                    Screen.ABOUT -> AboutScreen(onBack = back)
                    Screen.NEARBY -> NearbyCarsScreen(onBack = back, onConnecting = { screen = Screen.HOME })
                }
                if (supportPopup) SupportDialog(
                    onDismiss = { supportPopup = false },
                    onSupported = { markSupported() },
                )
            }
        }
    }

    /**
     * Debug-build-only headless entry point for the AA TLS receiver, so it can
     * be driven over adb rather than by tapping Developer Tools:
     *
     *   adb shell am start -n io.github.alimorgaan.aabridge/dev.caraalink.MainActivity --es aa_tls start \
     *       [--es aa_mode dial|listen] [--es aa_role server|client|auto] \
     *       [--ei aa_port 5277] [--es aa_host 127.0.0.1]
     *
     *   adb shell am start -n io.github.alimorgaan.aabridge/dev.caraalink.MainActivity --es aa_tls stop
     *
     * Refuses to do anything outside a debuggable build, so a release APK
     * can't be poked into opening a listener from the shell.
     */
    private fun maybeStartAaTls(intent: Intent?) {
        val probe = intent?.getStringExtra("aa_probe")
        if (probe != null) {
            if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
                when (probe) {
                    "stop" -> { AaVersionProbe.active?.stop(); AaSessionProbe.active?.stop() }
                    "session" -> if (AaSessionProbe.active == null) {
                        val cfg = AaSessionProbe.Config(
                            versionMajor = intent.getIntExtra("aa_vmajor", -1).takeIf { it >= 0 },
                            versionMinor = intent.getIntExtra("aa_vminor", -1).takeIf { it >= 0 },
                        )
                        AaSessionProbe(this, cfg).start()
                    }
                    else -> if (AaVersionProbe.active == null) AaVersionProbe(this).start()
                }
            }
            return
        }

        val op = intent?.getStringExtra("aa_tls") ?: return
        val debuggable = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!debuggable) {
            Log.w(TAG, "aa_tls='$op' ignored: not a debuggable build")
            return
        }

        if (op == "stop") {
            Log.i(TAG, "aa_tls: stopping receiver")
            AaTlsReceiver.active?.stop()
            Toast.makeText(this, "AA TLS receiver stopped", Toast.LENGTH_SHORT).show()
            return
        }

        if (AaTlsReceiver.active != null) {
            Log.w(TAG, "aa_tls: receiver already running, ignoring '$op'")
            Toast.makeText(this, "AA TLS receiver already running", Toast.LENGTH_SHORT).show()
            return
        }

        val mode = when (intent.getStringExtra("aa_mode")?.lowercase()) {
            "listen" -> AaTlsReceiver.Mode.LISTEN
            else -> AaTlsReceiver.Mode.DIAL
        }
        val role = when (intent.getStringExtra("aa_role")?.lowercase()) {
            "server" -> AaTlsReceiver.TlsRole.SERVER
            "client" -> AaTlsReceiver.TlsRole.CLIENT
            else -> AaTlsReceiver.TlsRole.AUTO
        }
        val config = AaTlsReceiver.Config(
            mode = mode,
            host = intent.getStringExtra("aa_host") ?: "127.0.0.1",
            port = intent.getIntExtra("aa_port", 5277),
            tlsRole = role,
        )
        Log.i(TAG, "aa_tls: starting receiver $config")
        AaTlsReceiver(this, config).start()
        Toast.makeText(this, "AA TLS receiver: ${mode.name}/${role.name}", Toast.LENGTH_SHORT).show()
    }

    private fun maybeAutoStartOnAttach(intent: Intent?, recreated: Boolean = false) {
        if (intent?.action != UsbManager.ACTION_USB_ACCESSORY_ATTACHED) return
        val fromRecents = intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        if (dev.caraalink.ui.isRepeatedAttach(recreated || fromRecents, dev.caraalink.connection.ConnectionStateHolder.snapshot)) return
        @Suppress("DEPRECATION")
        val accessory = intent.getParcelableExtra<UsbAccessory>(UsbManager.EXTRA_ACCESSORY) ?: return
        Log.i(TAG, "maybeAutoStartOnAttach: launched via attach intent, starting car session")
        val serviceIntent = Intent(this, UsbLinkForegroundService::class.java)
            .setAction(UsbLinkForegroundService.ACTION_START_SESSION)
            .putExtra(UsbManager.EXTRA_ACCESSORY, accessory)
        startForegroundService(serviceIntent)
    }

    // When the app is already running, picking it in the USB chooser delivers
    // the attach intent here instead of onCreate.
    override fun onResume() {
        super.onResume()
        val helped = AppSettings.supportHelped(this)
        val supported = AppSettings.supported(this)
        supportBanner = helped
        val byCar = carLaunchPending
        carLaunchPending = false
        val now = System.currentTimeMillis()
        if (!byCar && SupportAsk.isDue(helped, supported, AppSettings.supportPopupShownAt(this), now)) {
            supportPopup = true
            AppSettings.setSupportPopupShownAt(this, now)
        }
    }

    override fun onStart() {
        super.onStart()
        // Opening the app connects to the remembered car; the cable and a running attempt both win.
        // Without Nearby devices the scan can only fail; Home's Connect without a cable asks for it instead.
        val canScan = checkSelfPermission(dev.caraalink.ui.NEARBY_PERMISSION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (canScan && dev.caraalink.ui.shouldAutoConnect(dev.caraalink.connection.ConnectionStateHolder.snapshot, AppSettings.wirelessCar(this) != null)) {
            dev.caraalink.ui.startWireless(this)
        }
    }

    /** The car's USB attach opens the app right as the driver sets off; no pop-up then. */
    private fun launchedByCar(intent: Intent?): Boolean =
        intent?.action == UsbManager.ACTION_USB_ACCESSORY_ATTACHED &&
            intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0

    private fun markSupported() {
        AppSettings.setSupported(this)
        supportPopup = false
        Toast.makeText(this, R.string.support_thanks, Toast.LENGTH_LONG).show()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        Log.i(TAG, "onNewIntent: action=${intent.action}")
        carLaunchPending = launchedByCar(intent)
        maybeAutoStartOnAttach(intent)
        maybeStartAaTls(intent)
    }

}
