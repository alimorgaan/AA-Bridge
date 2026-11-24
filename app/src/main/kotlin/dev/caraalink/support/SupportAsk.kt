package dev.caraalink.support

import android.content.Context
import android.telephony.TelephonyManager

/** Where support goes, and when the monthly asks are due. */
object SupportAsk {
    const val INSTAPAY_URL = "https://ipn.eg/S/alimorgaan/instapay/4NfOYd"
    const val PAYPAL_URL = "https://paypal.me/alimorgaan"
    const val INTERVAL_MS = 30L * 24 * 60 * 60 * 1000

    // Brand colors: InstaPay purple sampled from its QR export, PayPal navy.
    val INSTAPAY_COLOR: Int = 0xFF552B78.toInt()
    val PAYPAL_COLOR: Int = 0xFF003087.toInt()

    /** SIM first (Egyptians abroad still bank in Egypt), then the network, then a Wi-Fi-only phone's time zone. */
    fun inEgypt(simCountry: String?, networkCountry: String?, timeZoneId: String): Boolean = when {
        !simCountry.isNullOrBlank() -> simCountry.equals("eg", ignoreCase = true)
        !networkCountry.isNullOrBlank() -> networkCountry.equals("eg", ignoreCase = true)
        else -> timeZoneId == "Africa/Cairo"
    }

    fun inEgypt(context: Context): Boolean {
        val phone = context.getSystemService(TelephonyManager::class.java)
        return inEgypt(phone?.simCountryIso, phone?.networkCountryIso, java.util.TimeZone.getDefault().id)
    }

    /** A clock moved backwards counts as due, so a wrong date can't silence the ask for months. */
    fun isDue(helped: Boolean, supported: Boolean, lastShownAt: Long, now: Long): Boolean =
        helped && !supported && (lastShownAt == 0L || now < lastShownAt || now - lastShownAt >= INTERVAL_MS)
}
