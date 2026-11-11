package dev.caraalink.ui

import dev.caraalink.connection.CarLink
import dev.caraalink.connection.ConnectionSnapshot
import dev.caraalink.connection.WirelessPhase
import dev.caraalink.wireless.RememberedCar
import dev.caraalink.connection.ConnectionPhase

enum class HomeState { READY, NEEDS_TAP, CONNECTED, SEARCHING, CONNECTING, NOT_FOUND, WIFI_OFF }

/** The server comes first: without it nothing works, car or not. */
fun homeState(serverRunning: Boolean, carConnected: Boolean, wireless: WirelessPhase = WirelessPhase.IDLE): HomeState = when {
    !serverRunning -> HomeState.NEEDS_TAP
    carConnected -> HomeState.CONNECTED
    wireless == WirelessPhase.SEARCHING || wireless == WirelessPhase.RECONNECTING -> HomeState.SEARCHING
    wireless == WirelessPhase.CONNECTING -> HomeState.CONNECTING
    wireless == WirelessPhase.NOT_FOUND || wireless == WirelessPhase.FAILED -> HomeState.NOT_FOUND
    wireless == WirelessPhase.WIFI_OFF -> HomeState.WIFI_OFF
    else -> HomeState.READY
}

/** The car the last search was for, so Try again doesn't silently switch to the remembered one. */
fun carToRetry(snapshot: ConnectionSnapshot, remembered: RememberedCar?): RememberedCar? = snapshot.target ?: remembered

/** Opening the app connects to the remembered car, unless a cable or wireless attempt is already running. */
fun shouldAutoConnect(snapshot: ConnectionSnapshot, hasRememberedCar: Boolean): Boolean =
    hasRememberedCar && snapshot.link == CarLink.NONE && !snapshot.phase.isCarConnected() && snapshot.wireless in setOf(
        WirelessPhase.IDLE, WirelessPhase.NOT_FOUND, WirelessPhase.FAILED, WirelessPhase.WIFI_OFF,
    )

fun ConnectionPhase.isCarConnected(): Boolean =
    this == ConnectionPhase.ATTACHED || this == ConnectionPhase.STREAMING || this == ConnectionPhase.RECONNECTING

data class SetupProgress(
    val permissionsGranted: Boolean,
    val devModeConfirmed: Boolean,
    val serverRunning: Boolean,
    val carConnectedOnce: Boolean,
)

/** First unfinished step, 1..4, or 5 when all are done. A running server proves developer mode is on. */
fun SetupProgress.currentStep(): Int = when {
    !permissionsGranted -> 1
    !(devModeConfirmed || serverRunning) -> 2
    !serverRunning -> 3
    !carConnectedOnce -> 4
    else -> 5
}
