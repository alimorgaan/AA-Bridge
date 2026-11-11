package dev.caraalink.connection

import dev.caraalink.wireless.NearbyDevice

/**
 * Lifecycle state of the USB link to the head unit. Shared between the
 * foreground service, the connection-diagnostics screen, and the USB
 * sniffer — all three read/update this instead of keeping their own copy.
 */
enum class ConnectionPhase {
    DISCONNECTED,
    ATTACHED,
    SNIFFING,
    STREAMING,
    RECONNECTING,
    ERROR,
}

enum class CarLink { NONE, CABLE, WIRELESS }

/** Wireless progress for Home; IDLE once connected or when nothing is happening. */
enum class WirelessPhase { IDLE, SEARCHING, CONNECTING, NOT_FOUND, FAILED, RECONNECTING, WIFI_OFF }

data class ConnectionSnapshot(
    val phase: ConnectionPhase = ConnectionPhase.DISCONNECTED,
    val accessoryManufacturer: String? = null,
    val accessoryModel: String? = null,
    val bytesRead: Long = 0,
    val bytesWritten: Long = 0,
    val lastError: String? = null,
    val reconnectAttempts: Int = 0,
    val carName: String? = null,
    val link: CarLink = CarLink.NONE,
    val wireless: WirelessPhase = WirelessPhase.IDLE,
    /** A device with the remembered car's name but another address, offered as a one-tap choice. */
    val suggestion: NearbyDevice? = null,
)

/**
 * Process-wide holder for [ConnectionSnapshot]. No persistence, no
 * cross-process IPC — just a shared in-memory spot so the service and the UI
 * agree on current state without wiring a bound-service interface for what's
 * still an early scaffold.
 */
object ConnectionStateHolder {
    @Volatile
    var snapshot: ConnectionSnapshot = ConnectionSnapshot()
        private set

    private val listeners = mutableListOf<(ConnectionSnapshot) -> Unit>()

    @Synchronized
    fun update(transform: (ConnectionSnapshot) -> ConnectionSnapshot) {
        snapshot = transform(snapshot)
        listeners.toList().forEach { it(snapshot) }
    }

    @Synchronized
    fun addListener(listener: (ConnectionSnapshot) -> Unit) {
        listeners.add(listener)
    }

    @Synchronized
    fun removeListener(listener: (ConnectionSnapshot) -> Unit) {
        listeners.remove(listener)
    }
}
