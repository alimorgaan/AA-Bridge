package dev.caraalink.ui

import dev.caraalink.connection.ConnectionPhase

enum class HomeState { READY, NEEDS_TAP, CONNECTED }

/** The server comes first: without it nothing works, car or not. */
fun homeState(serverRunning: Boolean, carConnected: Boolean): HomeState = when {
    !serverRunning -> HomeState.NEEDS_TAP
    carConnected -> HomeState.CONNECTED
    else -> HomeState.READY
}

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
