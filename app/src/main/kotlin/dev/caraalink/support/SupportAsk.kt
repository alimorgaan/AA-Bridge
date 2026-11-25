package dev.caraalink.support

import android.content.Context
import android.telephony.TelephonyManager

/** Where support goes, and when the monthly asks are due. */
object SupportAsk {
    const val INSTAPAY_URL = "https://ipn.eg/S/alimorgangranite/instapay/0efSwz"
    const val PAYPAL_URL = "https://paypal.me/alimorgaan"
    const val INTERVAL_MS = 30L * 24 * 60 * 60 * 1000
    // Weekly on the car: drivers who never open the app only get asked there.
    const val CAR_PAGE_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000

    // InstaPay purple sampled from its QR export; PayPal's gold button.
    val INSTAPAY_COLOR: Int = 0xFF552B78.toInt()
    val PAYPAL_GOLD: Int = 0xFFFFC439.toInt()

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
    fun isDue(helped: Boolean, supported: Boolean, lastShownAt: Long, now: Long, interval: Long = INTERVAL_MS): Boolean =
        helped && !supported && (lastShownAt == 0L || now < lastShownAt || now - lastShownAt >= interval)
}
