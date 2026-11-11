package dev.caraalink.video

import android.app.Presentation
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import dev.caraalink.R

enum class CarScreen { WAITING, STARTING, NOT_RESPONDING, CLOSED }

/** Full-screen car message shown whenever Android Auto isn't on the car screen. */
class CarStatusPresentation(
    context: Context,
    display: Display,
    private val screen: CarScreen,
    private val onOpenAa: () -> Unit,
) : Presentation(context, display) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(48), dp(32), dp(48), dp(32))
        }
        val title = when (screen) {
            CarScreen.WAITING -> R.string.car_waiting_title
            CarScreen.STARTING -> R.string.car_starting
            CarScreen.NOT_RESPONDING -> R.string.car_not_responding_title
            CarScreen.CLOSED -> R.string.car_closed_title
        }
        column.addView(text(title, 30f, Color.WHITE, bold = true))
        when (screen) {
            CarScreen.WAITING -> {
                column.addView(text(R.string.car_waiting_body, 18f, MUTED))
                column.addView(menuHint(R.string.menu_start_server))
            }
            CarScreen.NOT_RESPONDING -> {
                column.addView(text(R.string.car_not_responding_body, 18f, MUTED))
                column.addView(menuHint(R.string.menu_stop_server, R.string.menu_start_server))
            }
            CarScreen.STARTING -> column.addView(
                ProgressBar(context).apply { indeterminateTintList = ColorStateList.valueOf(ACCENT) },
                spaced(dp(48), dp(48)),
            )
            CarScreen.CLOSED -> column.addView(openButton(), spaced())
        }
        setContentView(FrameLayout(context).apply {
            setBackgroundColor(BACKGROUND)
            addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        })
    }

    private fun text(res: Int, size: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
        setText(res)
        textSize = size
        setTextColor(color)
        gravity = Gravity.CENTER
        if (bold) typeface = Typeface.DEFAULT_BOLD
        layoutParams = spaced()
    }

    /** The ⋮ button followed by the menu item(s) to tap, highlighted. */
    private fun menuHint(vararg items: Int) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        layoutParams = spaced(top = dp(24))
        addView(chip("⋮", Color.WHITE, SURFACE))
        for (item in items) {
            addView(arrow())
            addView(chip(context.getString(item), ACCENT, HIGHLIGHT))
        }
    }

    private fun chip(label: String, fg: Int, bg: Int) = TextView(context).apply {
        text = label
        textSize = 18f
        setTextColor(fg)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(18), dp(10), dp(18), dp(10))
        background = GradientDrawable().apply { setColor(bg); cornerRadius = dp(12).toFloat() }
    }

    private fun arrow() = TextView(context).apply {
        text = if (resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) "←" else "→"
        textSize = 20f
        setTextColor(MUTED)
        setPadding(dp(12), 0, dp(12), 0)
    }

    private fun openButton() = TextView(context).apply {
        setText(R.string.car_open_aa)
        textSize = 22f
        setTextColor(Color.WHITE)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(40), dp(18), dp(40), dp(18))
        background = GradientDrawable().apply { setColor(BRAND); cornerRadius = dp(40).toFloat() }
        isClickable = true
        setOnClickListener { onOpenAa() }
    }

    private fun spaced(width: Int = ViewGroup.LayoutParams.WRAP_CONTENT, height: Int = ViewGroup.LayoutParams.WRAP_CONTENT, top: Int = dp(16)) =
        LinearLayout.LayoutParams(width, height).apply { topMargin = top }

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics,
    ).toInt()

    private companion object {
        val BACKGROUND = Color.parseColor("#0E1116")
        val SURFACE = Color.parseColor("#1B2129")
        val HIGHLIGHT = Color.parseColor("#134E4A")
        val ACCENT = Color.parseColor("#5EEAD4")
        val BRAND = Color.parseColor("#0F766E")
        val MUTED = Color.parseColor("#9AA7B2")
    }
}
