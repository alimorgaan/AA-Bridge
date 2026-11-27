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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import dev.caraalink.R

enum class CarScreen { WAITING, STARTING, NOT_RESPONDING, CLOSED, SUPPORT }

/** Full-screen car message shown whenever Android Auto isn't on the car screen. */
class CarStatusPresentation(
    context: Context,
    display: Display,
    private val screen: CarScreen,
    private val onOpenAa: () -> Unit,
    /** Which payment the support page shows; Developer tools overrides it to capture both. */
    private val egypt: Boolean = dev.caraalink.support.SupportAsk.inEgypt(context),
) : Presentation(context, display) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (screen == CarScreen.SUPPORT) {
            setContentView(supportPage())
            return
        }
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
            CarScreen.SUPPORT -> R.string.support_if_helped
        }
        // Amber when the driver has to act, as on the phone.
        val needsDriver = screen == CarScreen.WAITING || screen == CarScreen.NOT_RESPONDING
        column.addView(text(title, 30f, if (needsDriver) AMBER else TEXT, bold = true))
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
            CarScreen.SUPPORT -> Unit
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

    /** Monthly support page: Arabic text on the right, the two QR codes on the left. */
    private fun supportPage(): View {
        val words = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(text(R.string.support_if_helped, 30f, TEXT, bold = true))
            addView(text(R.string.car_support_line, 20f, MUTED))
            addView(openButton(R.string.car_support_open_aa), spaced(top = dp(28)))
        }
        val codes = if (egypt) instapayCard() else paypalCard()
        // Portrait car screens are too narrow for text beside the codes, so stack them.
        val portrait = context.resources.displayMetrics.run { heightPixels > widthPixels }
        val row = LinearLayout(context).apply {
            orientation = if (portrait) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(24), dp(32), dp(24))
            if (portrait) {
                addView(words)
                addView(codes, spaced(top = dp(40)))
            } else {
                addView(words, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(codes)
            }
        }
        return FrameLayout(context).apply {
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(BACKGROUND)
            addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }

    /** InstaPay's own QR card for alimorgangranite@instapay: its logo, handle and signed link. */
    private fun instapayCard() = ImageView(context).apply {
        setImageResource(R.drawable.instapay_card)
        adjustViewBounds = true
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(CARD_DP))
        background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(14).toFloat() }
        clipToOutline = true
    }

    /** A white card in the same style: PayPal's logo, the paypal.me QR code and the link. */
    private fun paypalCard() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(20), dp(18), dp(20), dp(16))
        background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(14).toFloat() }
        addView(ImageView(context).apply { setImageResource(R.drawable.paypal_logo); adjustViewBounds = true },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(30)))
        addView(ImageView(context).apply { setImageResource(R.drawable.qr_paypal) },
            LinearLayout.LayoutParams(dp(QR_DP), dp(QR_DP)).apply { topMargin = dp(10) })
        addView(TextView(context).apply {
            text = "paypal.me/alimorgaan"
            textSize = 15f
            setTextColor(Color.parseColor("#1E2A3A"))
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
    }

    private fun openButton(label: Int = R.string.car_open_aa) = TextView(context).apply {
        setText(label)
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
        // The phone's O21 dashboard palette (ui/theme/Theme.kt Dash).
        val BACKGROUND = Color.parseColor("#0A0E12")
        val SURFACE = Color.parseColor("#121A20")
        val HIGHLIGHT = Color.parseColor("#0F2A2E")
        val ACCENT = Color.parseColor("#5EEAD4")
        val BRAND = Color.parseColor("#0D9488")
        val MUTED = Color.parseColor("#8AA3A8")
        val TEXT = Color.parseColor("#E8F1F2")
        val AMBER = Color.parseColor("#FBBF24")
        // About 5 car pixels per QR module, so a phone camera picks it up from the passenger seat.
        const val QR_DP = 200
        const val CARD_DP = 360
    }
}
