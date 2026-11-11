package dev.caraalink.wireless

data class NearbyDevice(val name: String, val address: String, val isCar: Boolean)

data class RememberedCar(val name: String, val address: String)

sealed interface CarMatch {
    data class Found(val device: NearbyDevice) : CarMatch
    data class SameName(val device: NearbyDevice) : CarMatch
    object None : CarMatch
}

/** By address only; a same-name device is just a suggestion, since every Emgrand reports the same model name. */
fun findCar(peers: List<NearbyDevice>, car: RememberedCar): CarMatch {
    peers.firstOrNull { it.address.equals(car.address, ignoreCase = true) }?.let { return CarMatch.Found(it) }
    peers.firstOrNull { it.name == car.name }?.let { return CarMatch.SameName(it) }
    return CarMatch.None
}

/** WPS primary device type "category-OUI-sub": 7 is displays, 8 is multimedia devices. */
fun isCarType(primaryDeviceType: String?): Boolean =
    primaryDeviceType?.substringBefore('-') in setOf("7", "8")

/** Remembered car first, then cars, then everything else, each by name. */
fun listOrder(peers: List<NearbyDevice>, remembered: RememberedCar?): List<NearbyDevice> =
    peers.sortedWith(
        compareByDescending<NearbyDevice> { remembered != null && it.address.equals(remembered.address, ignoreCase = true) }
            .thenByDescending { it.isCar }
            .thenBy { it.name.lowercase() },
    )
