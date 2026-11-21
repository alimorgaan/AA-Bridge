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
import android.view.InputDevice
import android.view.MotionEvent
import android.view.Surface
import android.view.WindowManager
import dev.caraalink.link.TouchGesture

/**
 * Owns what the car sees. One private virtual display (never seen by DeX)
 * renders into the encoder's surface and shows Android Auto or a status screen.
 * All work happens on the main thread.
 */
class CarDisplay(
    private val context: Context,
    private val onOpenAa: () -> Unit,
) {
    enum class Mode { STATUS, AA }

    private val main = Handler(Looper.getMainLooper())
    private var carDisplay: VirtualDisplay? = null
    private var presentation: Presentation? = null
    @Volatile var mode = Mode.STATUS
        private set
    /** Last status screen shown; stays put while Android Auto is on screen. */
    @Volatile var screen = CarScreen.STARTING
        private set
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

    fun showStatus(screen: CarScreen) = main.post {
        this.screen = screen
        mode = Mode.STATUS
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
                    mode = Mode.STATUS
                    CarStatusPresentation(context, display, screen, onOpenAa)
                }
            }.also { it.show() }
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
            onSurface = { s -> aaOnSurface?.invoke(s) },
            onTouch = { pointers, actionIndex, action -> aaOnTouch?.invoke(pointers, actionIndex, action) },
        )
    }

    /**
     * Car touch, every finger at once, delivered to whatever is on screen.
     * [scaleX]/[scaleY] turn car-screen pixels into car-display pixels.
     */
    fun touch(gesture: TouchGesture, scaleX: Float, scaleY: Float) = main.post {
        val view = presentation?.window?.decorView ?: return@post
        val now = SystemClock.uptimeMillis()
        if (gesture.action == MotionEvent.ACTION_DOWN) touchDownAt = now
        val props = gesture.pointers.map {
            MotionEvent.PointerProperties().apply { id = it.id; toolType = MotionEvent.TOOL_TYPE_FINGER }
        }.toTypedArray()
        val coords = gesture.pointers.map {
            MotionEvent.PointerCoords().apply { x = it.x * scaleX; y = it.y * scaleY; pressure = 1f; size = 1f }
        }.toTypedArray()
        val pointerAction = gesture.action == MotionEvent.ACTION_POINTER_DOWN || gesture.action == MotionEvent.ACTION_POINTER_UP
        val action = if (pointerAction) gesture.action or (gesture.actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT) else gesture.action
        val ev = MotionEvent.obtain(
            touchDownAt, now, action, props.size, props, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        view.dispatchTouchEvent(ev)
        ev.recycle()
    }

    /** Draws the current screen again, so a still picture still gives the encoder a fresh frame. */
    fun redraw() = main.post { presentation?.window?.decorView?.invalidate() }

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
