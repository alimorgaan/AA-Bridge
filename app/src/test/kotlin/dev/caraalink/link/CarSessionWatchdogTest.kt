package dev.caraalink.link

import dev.caraalink.video.CarScreen
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarSessionWatchdogTest {

    @Test
    fun releasedOrClosedNeverReopensByItself() {
        assertFalse(CarSession.mayAutoOpenAa(released = true, screen = CarScreen.STARTING))
        assertFalse(CarSession.mayAutoOpenAa(released = false, screen = CarScreen.CLOSED))
        assertTrue(CarSession.mayAutoOpenAa(released = false, screen = CarScreen.STARTING))
    }

    @Test
    fun serverCountsAsLostAfterThirtySecondsWithoutAConnection() {
        assertFalse(CarSession.lostServer(connectionActive = false, inactiveForMs = 29_999))
        assertTrue(CarSession.lostServer(connectionActive = false, inactiveForMs = 30_000))
        assertFalse(CarSession.lostServer(connectionActive = true, inactiveForMs = 60_000))
    }
}
