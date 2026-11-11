package dev.caraalink

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.widget.Toast
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import dev.caraalink.aa.AaTlsReceiver
import dev.caraalink.aa.AaSessionProbe
import dev.caraalink.aa.AaVersionProbe
import dev.caraalink.connection.ConnectionStateHolder
import dev.caraalink.diagnostics.ConnectionDiagnosticsActivity
import dev.caraalink.onboarding.OnboardingActivity
import dev.caraalink.service.UsbLinkForegroundService
import dev.caraalink.settings.SettingsActivity

/**
 * Placeholder entry point. Real bring-up flow (MediaProjection capture, USB
 * transport) lands once the CarbitLink wire protocol is confirmed — see
 * claude/roadmap.md. The buttons below are all real and usable today;
 * everything else in the app is still a stub.
 */
class MainActivity : Activity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val PREF_ONBOARDING_SHOWN = "onboarding_shown"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "onCreate: launch intent action=${intent?.action} extras=${intent?.extras}")
        dev.caraalink.diagnostics.CrashMarker.consumeLastCrash(this)?.let { summary ->
            Toast.makeText(this, "Restarted after a crash: $summary", Toast.LENGTH_LONG).show()
        }
        dev.caraalink.update.UpdateChecker.checkIfDue(this)
        render()
        maybeShowOnboarding()
        maybeAutoStartOnAttach(intent)
        maybeStartAaTls(intent)
        if (intent?.action != UsbManager.ACTION_USB_ACCESSORY_ATTACHED &&
            checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            // The car asks the phone for GPS (its own nav/clock), plus weather on the home screen.
            requestPermissions(arrayOf(
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION,
            ), 8)
        }
    }

    /**
     * Auto-starts the ADB handshake probe, but ONLY when this Activity was
     * actually launched by the USB_ACCESSORY_ATTACHED intent -- i.e. only
     * when the user picked CarAALink in Android's resolver chooser. This is
     * the fix for the previous attempt (claude/roadmap.md): wiring this to
     * UsbAttachReceiver instead fired on EVERY attach regardless of which
     * app was chosen, racing against CarbitLink even when CarbitLink was
     * picked. MainActivity only ever gets this intent when CarAALink is the
     * one chosen, so there's no race with CarbitLink anymore.
     */
    /**
     * Debug-build-only headless entry point for the AA TLS receiver, so it can
     * be driven over adb rather than by tapping Developer Tools:
     *
     *   adb shell am start -n dev.caraalink/.MainActivity --es aa_tls start \
     *       [--es aa_mode dial|listen] [--es aa_role server|client|auto] \
     *       [--ei aa_port 5277] [--es aa_host 127.0.0.1]
     *
     *   adb shell am start -n dev.caraalink/.MainActivity --es aa_tls stop
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

    private fun maybeShowOnboarding() {
        val prefs = getPreferences(MODE_PRIVATE)
        if (prefs.getBoolean(PREF_ONBOARDING_SHOWN, false)) return
        prefs.edit().putBoolean(PREF_ONBOARDING_SHOWN, true).apply()
        startActivity(Intent(this, OnboardingActivity::class.java))
    }

    override fun onResume() {
        super.onResume()
        render() // keeps the status line fresh without a dedicated observer here
    }

    private fun render() {
        val pad = dp(24)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(TextView(this).apply {
            text = "AA Bridge"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            setTextColor(Color.parseColor("#F2F5F7"))
        })

        root.addView(TextView(this).apply {
            text = "Status: ${ConnectionStateHolder.snapshot.phase}"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(Color.parseColor("#9AA7B2"))
            setPadding(0, dp(4), 0, dp(24))
        })

        primaryButton("Start head unit server") { dev.caraalink.aa.AndroidAutoServer.openSettings(this) }.also { root.addView(it) }
        spacer(root)
        outlineButton("Setup checklist") { open(OnboardingActivity::class.java) }.also { root.addView(it) }
        spacer(root)
        outlineButton("Connection diagnostics") { open(ConnectionDiagnosticsActivity::class.java) }.also { root.addView(it) }
        spacer(root)
        outlineButton("Settings") { open(SettingsActivity::class.java) }.also { root.addView(it) }
        spacer(root)
        outlineButton("Check for updates") { dev.caraalink.update.UpdateChecker.checkNow(this, manual = true) }.also { root.addView(it) }

        val frame = android.widget.FrameLayout(this)
        frame.addView(root, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        dev.caraalink.diagnostics.PerfStats.attachHud(this, frame)
        setContentView(frame)
    }

    private fun primaryButton(label: String, onClick: () -> Unit) = MaterialButton(this).apply {
        text = label
        isAllCaps = false // ALL CAPS buttons are what makes flat Material look dated/Holo-era
        textSize = 16f
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56))
        cornerRadius = dp(18)
        setPadding(dp(20), 0, dp(20), 0)
        elevation = dp(2).toFloat()
        setBackgroundColor(Color.parseColor("#36C9C6"))
        setTextColor(Color.parseColor("#0A0F12"))
    }

    private fun outlineButton(label: String, onClick: () -> Unit) = MaterialButton(
        this,
        null,
        com.google.android.material.R.attr.materialButtonOutlinedStyle,
    ).apply {
        text = label
        isAllCaps = false
        textSize = 16f
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56))
        cornerRadius = dp(18)
        setPadding(dp(20), 0, dp(20), 0)
        setTextColor(Color.parseColor("#F2F5F7"))
        strokeColor = android.content.res.ColorStateList.valueOf(Color.parseColor("#2C3640"))
        strokeWidth = dp(1)
        rippleColor = android.content.res.ColorStateList.valueOf(Color.parseColor("#4036C9C6"))
    }

    private fun spacer(parent: LinearLayout) {
        parent.addView(android.view.View(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(14))
        })
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    private fun open(activity: Class<*>) {
        startActivity(Intent(this, activity))
    }
}
