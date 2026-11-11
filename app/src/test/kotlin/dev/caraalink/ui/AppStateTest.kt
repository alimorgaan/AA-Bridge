package dev.caraalink.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class AppStateTest {

    @Test
    fun homeStates() {
        assertEquals(HomeState.NEEDS_TAP, homeState(serverRunning = false, carConnected = false))
        assertEquals(HomeState.NEEDS_TAP, homeState(serverRunning = false, carConnected = true))
        assertEquals(HomeState.READY, homeState(serverRunning = true, carConnected = false))
        assertEquals(HomeState.CONNECTED, homeState(serverRunning = true, carConnected = true))
    }
}
