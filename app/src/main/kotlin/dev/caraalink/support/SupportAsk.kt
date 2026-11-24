package dev.caraalink.support

/** Where support goes, and when the monthly asks are due. */
object SupportAsk {
    const val INSTAPAY_URL = "https://ipn.eg/S/alimorgaan/instapay/4NfOYd"
    const val PAYPAL_URL = "https://paypal.me/alimorgaan"
    const val INTERVAL_MS = 30L * 24 * 60 * 60 * 1000

    /** A clock moved backwards counts as due, so a wrong date can't silence the ask for months. */
    fun isDue(helped: Boolean, supported: Boolean, lastShownAt: Long, now: Long): Boolean =
        helped && !supported && (lastShownAt == 0L || now < lastShownAt || now - lastShownAt >= INTERVAL_MS)
}
