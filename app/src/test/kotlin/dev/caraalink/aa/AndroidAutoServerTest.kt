package dev.caraalink.aa

import dev.caraalink.video.CarScreen
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidAutoServerTest {

    @Test
    fun closedNeverAutoOpens() {
        assertFalse(AndroidAutoServer.shouldAutoOpen(CarScreen.CLOSED, serverRunning = true))
    }

    @Test
    fun waitingOpensOnceServerRuns() {
        assertTrue(AndroidAutoServer.shouldAutoOpen(CarScreen.WAITING, serverRunning = true))
        assertFalse(AndroidAutoServer.shouldAutoOpen(CarScreen.WAITING, serverRunning = false))
    }
}
