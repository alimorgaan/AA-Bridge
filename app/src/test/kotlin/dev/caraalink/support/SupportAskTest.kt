package dev.caraalink.support

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportAskTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 1_000 * day

    @Test
    fun dueOnlyForHelpedNonSupportersEveryThirtyDays() {
        assertTrue(SupportAsk.isDue(helped = true, supported = false, lastShownAt = 0, now = now))
        assertFalse(SupportAsk.isDue(helped = false, supported = false, lastShownAt = 0, now = now))
        assertFalse(SupportAsk.isDue(helped = true, supported = true, lastShownAt = 0, now = now))
        assertFalse(SupportAsk.isDue(helped = true, supported = false, lastShownAt = now - 30 * day + 1, now = now))
        assertTrue(SupportAsk.isDue(helped = true, supported = false, lastShownAt = now - 30 * day, now = now))
    }

    @Test
    fun aClockMovedBackwardsStillAsks() {
        assertTrue(SupportAsk.isDue(helped = true, supported = false, lastShownAt = now + 5 * day, now = now))
    }

    @Test
    fun egyptComesFromTheSimThenTheNetworkThenTheTimeZone() {
        assertTrue(SupportAsk.inEgypt(simCountry = "EG", networkCountry = "sa", timeZoneId = "Asia/Riyadh"))
        assertFalse(SupportAsk.inEgypt(simCountry = "ae", networkCountry = "eg", timeZoneId = "Africa/Cairo"))
        assertTrue(SupportAsk.inEgypt(simCountry = "", networkCountry = "eg", timeZoneId = "Europe/Paris"))
        assertTrue(SupportAsk.inEgypt(simCountry = null, networkCountry = null, timeZoneId = "Africa/Cairo"))
        assertFalse(SupportAsk.inEgypt(simCountry = null, networkCountry = "", timeZoneId = "Africa/Tunis"))
    }

    @Test
    fun theCarPageComesBackWeekly() {
        val week = SupportAsk.CAR_PAGE_INTERVAL_MS
        assertFalse(SupportAsk.isDue(helped = true, supported = false, lastShownAt = now - 7 * day + 1, now = now, interval = week))
        assertTrue(SupportAsk.isDue(helped = true, supported = false, lastShownAt = now - 7 * day, now = now, interval = week))
    }
}
