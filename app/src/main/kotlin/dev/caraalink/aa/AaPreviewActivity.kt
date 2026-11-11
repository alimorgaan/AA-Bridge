package dev.caraalink.aa

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import dev.caraalink.R
import dev.caraalink.settings.SettingsActivity
import dev.caraalink.video.CarHomePresentation
import dev.caraalink.video.buildHomeContent

/**
 * On-phone stand-in for the car screen, for testing without the car: opens
 * on CarAALink's own home screen (same view-building code the real car
 * Presentation uses, via [buildHomeContent]) and only starts the actual AA
 * session -- [AaSessionProbe] plus a decoded, interactive video view -- once
 * the "Android Auto" tile is tapped, same as it works on the car.
 */
class AaPreviewActivity : Activity() {

    private var decoder: AaVideoDecoder? = null
    private var probe: AaSessionProbe? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showHome()
    }

    private fun showHome() {
        val home = buildHomeContent(
            this,
            listOf(
                CarHomePresentation.Tile("Android Auto", R.drawable.ic_tile_android_auto, Color.parseColor("#1E8E3E")) { showAaSession() },
                CarHomePresentation.Tile("Settings", R.drawable.ic_tile_settings, Color.parseColor("#5F6368")) {
                    startActivity(Intent(this, SettingsActivity::class.java))
                },
            ),
        )
        setContentView(home.root)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showAaSession() {
        // Must match what AaSessionProbe actually negotiates with the phone
        // (also configFromSettings) -- a decoder configured for a different
        // size than what's really arriving silently fails to produce frames.
        val config = AaServiceDiscovery.configFromSettings(this, preview = true)

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val surfaceView = SurfaceView(this)
        root.addView(surfaceView, FrameLayout.LayoutParams(1, 1, Gravity.CENTER))

        // AA only offers 16:9-family resolutions, but a modern phone's own
        // screen is nowhere close to 16:9 in either orientation -- FIT
        // (honouring the owner's car setting, meant for the car's own
        // panel) made that mismatch visible as letterbox bars, worse-looking
        // in landscape even though the actual proportional mismatch is the
        // same in both orientations. Car Preview always crops to fill
        // instead, same tradeoff FILL mode already makes for a real car's
        // odd-aspect panel -- this is a dev preview tool, not meant to
        // reproduce the car's exact letterboxing.
        root.clipChildren = true
        root.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val targetW = config.resolution.width.toFloat()
            val targetH = config.resolution.height.toFloat()
            val availW = v.width
            val availH = v.height
            if (availW <= 0 || availH <= 0) return@addOnLayoutChangeListener
            val scale = maxOf(availW / targetW, availH / targetH)
            val lp = surfaceView.layoutParams as FrameLayout.LayoutParams
            val newW = (targetW * scale).toInt()
            val newH = (targetH * scale).toInt()
            if (lp.width != newW || lp.height != newH) {
                lp.width = newW
                lp.height = newH
                surfaceView.layoutParams = lp
            }
        }

        // Map this view's own pixels onto the AA video's coordinate space
        // (the resolution we told the phone we're rendering at) and forward
        // as touch events on the input channel -- lets this screen stand in
        // for the car head unit's touchscreen. ACTION_MOVE is coalesced to
        // one send per frame tick rather than one per raw pointer-move
        // event: at full rate a swipe can fire dozens of MotionEvents before
        // the previous send even reaches the phone, which is what made touch
        // feel laggy (on top of the logging fix in AaTlsChannel).
        // Multi-touch (pinch/rotate in Maps etc.): AA's TouchEvent.touch_location
        // is a repeated field (confirmed from mrmees/open-android-auto's
        // docs/protocol-reference.md, GPL-3.0, field numbers only) -- every
        // currently-down finger goes in one message, with actionIndex saying
        // which one triggered this particular down/up/move.
        var lastMoveAt = 0L
        surfaceView.setOnTouchListener { view, event ->
            val action = when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> 0
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> 1
                MotionEvent.ACTION_MOVE -> {
                    val now = System.currentTimeMillis()
                    if (now - lastMoveAt < 30) return@setOnTouchListener true
                    lastMoveAt = now
                    2
                }
                else -> return@setOnTouchListener false
            }
            val pointers = (0 until event.pointerCount).map { i ->
                val x = (event.getX(i) / view.width * config.resolution.width).toInt().coerceIn(0, config.resolution.width - 1)
                val y = (event.getY(i) / view.height * config.resolution.height).toInt().coerceIn(0, config.resolution.height - 1)
                Triple(x, y, event.getPointerId(i))
            }
            probe?.sendTouch(pointers, event.actionIndex, action)
            true
        }

        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                val dec = AaVideoDecoder(config.resolution.width, config.resolution.height)
                dec.start(holder.surface)
                decoder = dec

                val target = AaSessionProbe.active
                    ?: AaSessionProbe(this@AaPreviewActivity, AaSessionProbe.Config(preview = true))
                target.attachVideoSink { ts, nal -> decoder?.feed(ts, nal) }
                probe = target
                if (AaSessionProbe.active == null) target.start()
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {}

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                // Same fix as CarSession's real-car path: actually end the
                // session instead of just detaching the sink, so backing
                // out of the preview disconnects from the phone's own
                // head-unit-server mode instead of leaving it connected.
                probe?.stop()
                probe?.videoSink = null
                decoder?.stop()
                decoder = null
            }
        })

        root.addView(
            Button(this).apply {
                text = "Back to car home"
                setOnClickListener { showHome() }
            },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START),
        )
        dev.caraalink.diagnostics.PerfStats.attachHud(this, root)

        setContentView(root)
    }

    override fun onBackPressed() {
        finish()
    }
}
