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
