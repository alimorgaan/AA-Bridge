package dev.caraalink.ui

import dev.caraalink.connection.CarLink
import dev.caraalink.connection.ConnectionSnapshot
import dev.caraalink.connection.WirelessPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun wirelessHomeStates() {
        assertEquals(HomeState.NEEDS_TAP, homeState(false, false, WirelessPhase.SEARCHING))
        assertEquals(HomeState.SEARCHING, homeState(true, false, WirelessPhase.SEARCHING))
        assertEquals(HomeState.SEARCHING, homeState(true, false, WirelessPhase.RECONNECTING))
        assertEquals(HomeState.CONNECTING, homeState(true, false, WirelessPhase.CONNECTING))
        assertEquals(HomeState.NOT_FOUND, homeState(true, false, WirelessPhase.NOT_FOUND))
        assertEquals(HomeState.NOT_FOUND, homeState(true, false, WirelessPhase.FAILED))
        assertEquals(HomeState.WIFI_OFF, homeState(true, false, WirelessPhase.WIFI_OFF))
        assertEquals(HomeState.CONNECTED, homeState(true, true, WirelessPhase.IDLE))
    }

    @Test
    fun autoConnectOnlyWhenIdle() {
        val idle = ConnectionSnapshot()
        assertTrue(shouldAutoConnect(idle, hasRememberedCar = true))
        assertTrue(shouldAutoConnect(idle.copy(wireless = WirelessPhase.NOT_FOUND), true))
        assertFalse(shouldAutoConnect(idle, hasRememberedCar = false))
        assertFalse(shouldAutoConnect(idle.copy(link = CarLink.CABLE), true))
        assertFalse(shouldAutoConnect(idle.copy(link = CarLink.WIRELESS, wireless = WirelessPhase.SEARCHING), true))
        assertFalse(shouldAutoConnect(idle.copy(wireless = WirelessPhase.CONNECTING), true))
    }

    @Test
    fun autoConnectNeverBesideACableSession() {
        val cableAfterRetry = ConnectionSnapshot(phase = dev.caraalink.connection.ConnectionPhase.STREAMING)
        assertFalse(shouldAutoConnect(cableAfterRetry, hasRememberedCar = true))
    }

    @Test
    fun retriesTheCarTheServiceWasLookingFor() {
        val picked = dev.caraalink.wireless.RememberedCar("Car B", "02:bb:bb:bb:bb:bb")
        val remembered = dev.caraalink.wireless.RememberedCar("Car A", "02:aa:aa:aa:aa:aa")
        assertEquals(picked, carToRetry(ConnectionSnapshot(target = picked), remembered))
        assertEquals(remembered, carToRetry(ConnectionSnapshot(), remembered))
    }
}
