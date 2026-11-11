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
import dev.caraalink.settings.SettingsActivity
import dev.caraalink.settings.AppSettings
import dev.caraalink.ui.HomeScreen
import dev.caraalink.ui.SetupScreen
import dev.caraalink.ui.theme.AaBridgeTheme

enum class Screen { SETUP, HOME, SETTINGS, HELP, ABOUT }

/** The phone app: one Compose activity; also where Android sends the car's USB attach. */
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Log.i(TAG, "onCreate: launch intent action=${intent?.action} extras=${intent?.extras}")
        dev.caraalink.diagnostics.CrashMarker.consumeLastCrash(this)?.let { summary ->
            Toast.makeText(this, "Restarted after a crash: $summary", Toast.LENGTH_LONG).show()
        }
        dev.caraalink.update.UpdateChecker.checkIfDue(this)
        maybeAutoStartOnAttach(intent)
        maybeStartAaTls(intent)
        val firstScreen = if (AppSettings.setupComplete(this)) Screen.HOME else Screen.SETUP
        setContent {
            AaBridgeTheme {
                var screen by rememberSaveable { mutableStateOf(firstScreen) }
                BackHandler(enabled = screen != Screen.HOME && AppSettings.setupComplete(this)) { screen = Screen.HOME }
                when (screen) {
                    Screen.SETUP -> SetupScreen(onDone = { screen = Screen.HOME })
                    else -> HomeScreen(
                        onOpenSettings = { startActivity(Intent(this, SettingsActivity::class.java)) },
                        onOpenHelp = { screen = Screen.SETUP },
                    )
                }
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

    private fun maybeAutoStartOnAttach(intent: Intent?) {
        if (intent?.action != UsbManager.ACTION_USB_ACCESSORY_ATTACHED) return
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
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        Log.i(TAG, "onNewIntent: action=${intent.action}")
        maybeAutoStartOnAttach(intent)
        maybeStartAaTls(intent)
    }

}
