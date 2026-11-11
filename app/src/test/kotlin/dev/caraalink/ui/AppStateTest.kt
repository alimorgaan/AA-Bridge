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

    @Test
    fun setupSteps() {
        assertEquals(1, SetupProgress(false, false, false, false).currentStep())
        assertEquals(2, SetupProgress(true, false, false, false).currentStep())
        assertEquals(3, SetupProgress(true, true, false, false).currentStep())
        assertEquals(4, SetupProgress(true, false, true, false).currentStep())
        assertEquals(5, SetupProgress(true, false, true, true).currentStep())
    }
}
