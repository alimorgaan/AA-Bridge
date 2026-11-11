package dev.caraalink.video

import android.app.Presentation
import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.MotionEvent
import android.view.Surface
import android.view.WindowManager

/**
 * Owns what the car sees. One private virtual display (never seen by DeX)
 * renders into the encoder's surface and shows the home screen or Android Auto.
 * All work happens on the main thread.
 */
class CarDisplay(
    private val context: Context,
    private val tiles: List<CarHomePresentation.Tile>,
    private val onExit: () -> Unit,
) {
    enum class Mode { HOME, AA }

    private val main = Handler(Looper.getMainLooper())
    private var carDisplay: VirtualDisplay? = null
    private var presentation: Presentation? = null
    @Volatile var mode = Mode.HOME
        private set
    private var status = "Connected"
    private var info = ""
    private var touchDownAt = 0L

    /** Point the car display at a (new) encoder surface of [w]x[h]. */
    fun attach(surface: Surface, w: Int, h: Int) = main.post {
        val dpi = (minOf(w, h) / 720f * 213).toInt().coerceIn(120, 320)
        val existing = carDisplay
        if (existing == null) {
            val dm = context.getSystemService(DisplayManager::class.java)
            carDisplay = dm.createVirtualDisplay("CarAALink", w, h, dpi, surface, 0)
        } else {
            existing.resize(w, h, dpi)
            existing.surface = surface
        }
        showCurrent()
    }

    fun setStatus(text: String) = main.post {
        status = text
        (presentation as? CarHomePresentation)?.setStatus(text)
    }

    fun setInfo(text: String) = main.post {
        info = text
        (presentation as? CarHomePresentation)?.setInfo(text)
    }

    fun showHome() = main.post {
        mode = Mode.HOME
        showCurrent()
    }

    /**
     * Shows a live Android Auto session at [videoWidth]x[videoHeight] (the
     * resolution actually negotiated with the phone -- see
     * AaServiceDiscovery.configFromSettings()). [onSurface]/[onTouch] wire
     * the decoder and touch forwarding; the caller (CarSession) owns the
     * actual AaSessionProbe/AaVideoDecoder/AaAudioPlayer.
     */
    fun showAa(
        videoWidth: Int,
        videoHeight: Int,
        widthMargin: Int = 0,
        heightMargin: Int = 0,
        onSurface: (Surface?) -> Unit,
        onTouch: (pointers: List<Triple<Int, Int, Int>>, actionIndex: Int, action: Int) -> Unit,
    ) = main.post {
        aaVideoSize = videoWidth to videoHeight
        aaMargin = widthMargin to heightMargin
        aaOnSurface = onSurface
        aaOnTouch = onTouch
        mode = Mode.AA
        showCurrent()
    }

    private var aaVideoSize: Pair<Int, Int>? = null
    private var aaMargin: Pair<Int, Int> = 0 to 0
    private var aaOnSurface: ((Surface?) -> Unit)? = null
    private var aaOnTouch: ((pointers: List<Triple<Int, Int, Int>>, actionIndex: Int, action: Int) -> Unit)? = null

    private fun showCurrent() {
        val display = carDisplay?.display ?: return
        presentation?.dismiss()
        presentation = try {
            when {
                mode == Mode.AA && aaVideoSize != null -> aaPresentation(display)
                else -> {
                    mode = Mode.HOME
                    CarHomePresentation(context, display, tiles, onExit = ::showHome).also {
                        it.show()
                        it.setStatus(status)
                        it.setInfo(info)
                    }
                }
            }.also { if (it !is CarHomePresentation) it.show() }
        } catch (e: WindowManager.InvalidDisplayException) {
            Log.e(TAG, "presentation failed", e)
            null
        }
    }

    private fun aaPresentation(display: Display): Presentation {
        val (w, h) = aaVideoSize!!
        val (wm, hm) = aaMargin
        return AaCarPresentation(
            context, display, w, h, wm, hm,
            onExit = onExit,
            onSurface = { s -> aaOnSurface?.invoke(s) },
            onTouch = { pointers, actionIndex, action -> aaOnTouch?.invoke(pointers, actionIndex, action) },
        )
    }

    /** Car touch in car-display pixels, delivered to whatever is on screen. */
    fun touch(action: Int, x: Float, y: Float) = main.post {
        val view = presentation?.window?.decorView ?: return@post
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) touchDownAt = now
        val ev = MotionEvent.obtain(touchDownAt, now, action, x, y, 0)
        view.dispatchTouchEvent(ev)
        ev.recycle()
    }

    fun release() = main.post {
        presentation?.dismiss()
        presentation = null
        carDisplay?.release()
        carDisplay = null
    }

    companion object {
        private const val TAG = "CarDisplay"
    }
}
