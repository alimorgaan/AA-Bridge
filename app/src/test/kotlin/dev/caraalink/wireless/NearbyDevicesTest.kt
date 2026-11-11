package dev.caraalink.wireless

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyDevicesTest {
    private val car = RememberedCar("EC-KY-4350", "02:08:22:db:98:fb")

    @Test
    fun matchesByAddressOnly() {
        val renamed = NearbyDevice("Other name", "02:08:22:db:98:fb", true)
        val neighbour = NearbyDevice("EC-KY-4350", "02:aa:bb:cc:dd:ee", true)
        assertEquals(CarMatch.Found(renamed), findCar(listOf(neighbour, renamed), car))
        assertEquals(CarMatch.SameName(neighbour), findCar(listOf(neighbour), car))
        assertEquals(CarMatch.None, findCar(listOf(NearbyDevice("TV", "02:11:11:11:11:11", false)), car))
    }

    @Test
    fun carTypesAreDisplayOrMultimedia() {
        assertTrue(isCarType("8-0050F204-2"))
        assertTrue(isCarType("7-0050F204-1"))
        assertFalse(isCarType("10-0050F204-5"))
        assertFalse(isCarType(null))
    }
}
