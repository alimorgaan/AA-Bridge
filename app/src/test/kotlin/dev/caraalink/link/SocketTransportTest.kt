package dev.caraalink.link

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SocketTransportTest {
    @Test
    fun acceptsOnlyTheWifiDirectInterface() {
        assertTrue(SocketTransport.acceptsInterface("p2p-wlan0-0"))
        assertFalse(SocketTransport.acceptsInterface("wlan0"))
        assertFalse(SocketTransport.acceptsInterface("lo"))
        assertFalse(SocketTransport.acceptsInterface(null))
    }
}
