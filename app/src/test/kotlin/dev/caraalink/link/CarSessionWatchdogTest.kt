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
        assertFalse(CarSession.mayAutoOpenAa(released = false, screen = CarScreen.SUPPORT))
        assertTrue(CarSession.mayAutoOpenAa(released = false, screen = CarScreen.STARTING))
    }

    @Test
    fun aStillScreenThatAnswersIsNotStalled() {
        assertFalse(CarSession.aaStalled(silentMs = 5_000, videoStaleMs = 60_000, silentLimitMs = 10_000))
        assertTrue(CarSession.aaStalled(silentMs = 10_001, videoStaleMs = 10_001, silentLimitMs = 10_000))
        // The clock redraws every minute, so no frame for longer is a frozen picture.
        assertTrue(CarSession.aaStalled(silentMs = 0, videoStaleMs = 90_001, silentLimitMs = 10_000))
    }

    @Test
    fun serverCountsAsLostAfterThirtySecondsWithoutAConnection() {
        assertFalse(CarSession.lostServer(connectionActive = false, inactiveForMs = 29_999))
        assertTrue(CarSession.lostServer(connectionActive = false, inactiveForMs = 30_000))
        assertFalse(CarSession.lostServer(connectionActive = true, inactiveForMs = 60_000))
    }
}
