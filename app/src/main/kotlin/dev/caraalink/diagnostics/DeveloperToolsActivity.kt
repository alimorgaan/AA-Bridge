package dev.caraalink.diagnostics

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton

/**
 * What's actually still useful day to day: export logs, and the interactive
 * on-phone AA preview. The early RE-phase probes (USB sniffer, ADB
 * handshake probe, raw AA TLS receiver, AA version-only probe) are
 * superseded by the real session path (AaSessionProbe) and were removed
 * from here -- their code is untouched (AaTlsReceiver.kt, AaVersionProbe.kt,
 * UsbLinkForegroundService's sniffer/ADB-probe actions) in case protocol
 * work ever needs to isolate a single rung again.
 */
class DeveloperToolsActivity : Activity() {

    companion object {
        private const val TAG = "DeveloperToolsActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = dp(24)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.parseColor("#0E1116"))
        }

        root.addView(TextView(this).apply {
            text = "Developer tools"
            setTextColor(Color.parseColor("#F2F5F7"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            setPadding(0, 0, 0, dp(20))
        })

        root.addView(button("Export latest log") { exportLatestLog() })
        root.addView(button("Save log to Downloads") { saveLogToDownloads() })
        root.addView(button("Clear logs") { clearLogs() })

        root.addView(section("Car Preview: starts (or attaches to) a real Android Auto session on this " +
            "phone, with decoded video and touch, for testing without the car."))

        root.addView(button("Car Preview") {
            startActivity(Intent(this@DeveloperToolsActivity, dev.caraalink.aa.AaPreviewActivity::class.java))
        })
        root.addView(button("Connection diagnostics") {
            startActivity(Intent(this@DeveloperToolsActivity, ConnectionDiagnosticsActivity::class.java))
        })

        setContentView(
            ScrollView(this).apply {
                addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            },
        )
    }

    private fun section(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(Color.parseColor("#9AA7B2"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setPadding(0, dp(16), 0, dp(8))
    }

    private fun button(label: String, onClick: () -> Unit) = MaterialButton(
        this,
        null,
        com.google.android.material.R.attr.materialButtonOutlinedStyle,
    ).apply {
        text = label
        isAllCaps = false
        textSize = 15f
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply {
            topMargin = dp(6)
        }
        cornerRadius = dp(16)
        setTextColor(Color.parseColor("#F2F5F7"))
        strokeColor = ColorStateList.valueOf(Color.parseColor("#2C3640"))
        strokeWidth = dp(1)
        rippleColor = ColorStateList.valueOf(Color.parseColor("#4036C9C6"))
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    private fun exportLatestLog() {
        // Every subsystem's DiagnosticLogger now writes into the one shared,
        // already-interleaved current log file -- nothing to merge anymore.
        val logger = DiagnosticLogger(this, "app")
        val file = logger.latestLogFile()
        if (!file.exists() || file.length() == 0L) {
            Toast.makeText(this, "No logs yet", Toast.LENGTH_LONG).show()
            return
        }
        Log.i(TAG, "exportLatestLog: sharing ${file.name} (${file.length()} bytes)")
        Toast.makeText(this, "Sharing ${file.name}", Toast.LENGTH_SHORT).show()
        startActivity(
            Intent.createChooser(
                logger.shareFileIntent(file),
                "Share AA Bridge logs",
            )
        )
    }

    private fun clearLogs() {
        DiagnosticLogger.clearAll(this)
        Toast.makeText(this, "Logs cleared", Toast.LENGTH_SHORT).show()
    }

    private fun saveLogToDownloads() {
        val logger = DiagnosticLogger(this, "app")
        val file = logger.latestLogFile()
        if (!file.exists() || file.length() == 0L) {
            Toast.makeText(this, "No logs yet", Toast.LENGTH_LONG).show()
            return
        }
        val name = logger.saveToDownloads(file)
        Toast.makeText(
            this,
            if (name != null) "Saved $name to Downloads" else "Save failed",
            Toast.LENGTH_LONG,
        ).show()
    }
}
