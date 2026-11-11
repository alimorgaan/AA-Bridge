package dev.caraalink.diagnostics

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import dev.caraalink.settings.AppSettings

/**
 * Lightweight, purely-local instrumentation of the two video pipelines this
 * app already runs (CarbitLink mirror encode, and the AA receiver's decode).
 * No protocol bytes here -- this only counts and times frames the app itself
 * already produces/consumes, same as any app's own FPS counter.
 */
private class FrameRateCounter {
    private var windowStart = 0L
    private var count = 0
    private var bytes = 0L
    @Volatile var fps = 0f
    @Volatile var kbps = 0f

    @Synchronized
    fun record(size: Int) {
        val now = System.currentTimeMillis()
        if (windowStart == 0L) windowStart = now
        count++
        bytes += size
        val elapsed = now - windowStart
        if (elapsed >= 1000) {
            fps = count * 1000f / elapsed
            kbps = bytes * 8f / elapsed // bytes/ms * 8 = kbit/s
            windowStart = now
            count = 0
            bytes = 0
        }
    }
}

object PerfStats {
    private val mirror = FrameRateCounter()
    private val aa = FrameRateCounter()
    @Volatile var mirrorFramesDropped = 0L
    @Volatile var aaReconnects = 0

    // AaVideoDecoder.feed() path, one counter per outcome so a silent
    // input-buffer miss (previously indistinguishable from a normal
    // successful decode) is now visible instead of just not happening.
    // All writes happen from feed()'s single @Synchronized caller, so plain
    // Long counters are fine -- no concurrent writers to race.
    @Volatile var aaAccessUnitsReceived = 0L
    @Volatile var aaSubmitted = 0L
    @Volatile var aaInputBufferUnavailable = 0L
    @Volatile var aaDecodeExceptions = 0L
    @Volatile var aaFramesRendered = 0L

    fun recordMirrorFrame(size: Int) = mirror.record(size)
    fun recordAaFrame(size: Int) = aa.record(size)
    fun recordAaAccessUnit() { aaAccessUnitsReceived++ }
    fun recordAaSubmitted() { aaSubmitted++ }
    fun recordAaInputBufferUnavailable() { aaInputBufferUnavailable++ }
    fun recordAaDecodeException() { aaDecodeExceptions++ }
    fun recordAaFrameRendered() { aaFramesRendered++ }

    fun summary(): String {
        val parts = mutableListOf<String>()
        if (mirror.fps > 0) parts += "Mirror ${mirror.fps.toInt()}fps/${mirror.kbps.toInt()}kbps"
        if (aa.fps > 0) parts += "AA ${aa.fps.toInt()}fps/${aa.kbps.toInt()}kbps"
        if (mirrorFramesDropped > 0) parts += "dropped $mirrorFramesDropped"
        if (aaReconnects > 0) parts += "AA reconnects $aaReconnects"
        if (aaAccessUnitsReceived > 0) {
            parts += aaDecodeSummary()
        }
        return if (parts.isEmpty()) "No active stream" else parts.joinToString("  ")
    }

    fun aaDecodeSummary(): String =
        "AA decode: recv=$aaAccessUnitsReceived sub=$aaSubmitted " +
            "miss=$aaInputBufferUnavailable exc=$aaDecodeExceptions rend=$aaFramesRendered"

    /**
     * Adds a small translucent HUD to [root] when Settings > Debug >
     * "On-screen debug overlay" is on -- works the same on the phone
     * (MainActivity, AaPreviewActivity) and the car (AaCarPresentation),
     * ticking every 500ms while attached.
     */
    fun attachHud(context: Context, root: FrameLayout) {
        if (!AppSettings.debugOverlay(context)) return
        val text = TextView(context).apply {
            setTextColor(0xFF9AFF9A.toInt())
            setBackgroundColor(0x99000000.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(dp(context, 6), dp(context, 2), dp(context, 6), dp(context, 2))
        }
        val handler = Handler(Looper.getMainLooper())
        val tick = object : Runnable {
            override fun run() {
                text.text = summary()
                handler.postDelayed(this, 500)
            }
        }
        text.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { tick.run() }
            override fun onViewDetachedFromWindow(v: View) { handler.removeCallbacks(tick) }
        })
        root.addView(text, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.TOP or android.view.Gravity.END))
    }

    private fun dp(context: Context, v: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics).toInt()
}
