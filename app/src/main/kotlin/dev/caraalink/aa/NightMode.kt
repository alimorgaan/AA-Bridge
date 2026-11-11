package dev.caraalink.aa

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Day/night for AA's night-mode sensor, from the sun's position at the phone's last known location. */
object NightMode {

    /** Null when there's no location, so nothing is sent and AA keeps its own default. */
    fun current(context: Context): Boolean? =
        lastLocation(context)?.let { isNight(it.latitude, it.longitude, System.currentTimeMillis()) }

    private fun lastLocation(context: Context): Location? {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        val lm = context.getSystemService(LocationManager::class.java)
        return try {
            lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
        } catch (e: SecurityException) {
            null
        }
    }

    /** Sun below the standard sunset altitude (-0.833°). Low-precision Astronomical Almanac formula. */
    fun isNight(latitude: Double, longitude: Double, epochMillis: Long): Boolean {
        val n = epochMillis / 86_400_000.0 + 2440587.5 - 2451545.0
        val meanLongitude = 280.460 + 0.9856474 * n
        val anomaly = Math.toRadians(357.528 + 0.9856003 * n)
        val eclipticLongitude = Math.toRadians(meanLongitude + 1.915 * sin(anomaly) + 0.020 * sin(2 * anomaly))
        val obliquity = Math.toRadians(23.439 - 0.0000004 * n)
        val declination = asin(sin(obliquity) * sin(eclipticLongitude))
        val rightAscension = Math.toDegrees(atan2(cos(obliquity) * sin(eclipticLongitude), cos(eclipticLongitude)))
        val siderealDegrees = (18.697374558 + 24.06570982441908 * n) * 15
        val hourAngle = Math.toRadians(siderealDegrees + longitude - rightAscension)
        val lat = Math.toRadians(latitude)
        val altitude = Math.toDegrees(asin(sin(lat) * sin(declination) + cos(lat) * cos(declination) * cos(hourAngle)))
        return altitude < -0.833
    }
}
