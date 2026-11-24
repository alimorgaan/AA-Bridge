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
}
