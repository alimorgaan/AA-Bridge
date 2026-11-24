package dev.caraalink.link

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The bytes below were sent to port 5903 by a real Maxus D90 head unit. */
class LegacyControlSessionTest {

    private fun hex(s: String): ByteArray = s.trim().split(" ").map { it.toInt(16).toByte() }.toByteArray()

    private val logs = ArrayList<String>()
    private fun session() = LegacyControlSession(log = { logs += it })

    @Test
    fun answersHeartbeatWithScreenStateAndZeroTransferStatus() {
        // The car sends this every 2 seconds. The reply is the screen state, then 0.
        val on = LegacyControlSession({ logs += it }, { true }).feed(hex("00 00 80 00"))
        assertArrayEquals(hex("00 00 00 01 00 00 00 00"), on.single())
        val off = LegacyControlSession({ logs += it }, { false }).feed(hex("00 00 80 00"))
        assertArrayEquals(hex("FF FF FF FF 00 00 00 00"), off.single())
    }

    @Test
    fun answersAppVersionWithTheOfficialVersionCode() {
        val replies = session().feed(hex("00 00 00 09"))
        assertEquals(1, replies.size)
        assertArrayEquals(hex("00 00 02 3E"), replies[0]) // 574
    }

    @Test
    fun answersEnvironmentWithAnEmptyString() {
        val replies = session().feed(hex("00 00 80 01"))
        assertArrayEquals(hex("00 00 00 00"), replies.single())
    }

    @Test
    fun answersCheckUpdateWithNoUpdate() {
        // An update check from the car: some numbers about itself, then two text strings.
        val bytes = hex(
            "00 00 80 3D 00 00 00 04 00 00 00 00 00 00 15 21 00 00 CB 70 " +
                "00 00 00 0B 53 44 30 39 2E 35 2E 30 2E 31 32 " +
                "00 00 00 0C 6E 65 74 2E 65 61 73 79 63 6F 6E 6E",
        )
        val replies = session().feed(bytes)
        assertArrayEquals(hex("00 00 00 08") + "noupdate".toByteArray(), replies.single())
        assertTrue(logs.any { it.contains("machineId=5409") && it.contains("versionName=SD09.5.0.12") })
    }

    @Test
    fun handlesCommandsArrivingInPiecesAsTheCarSendsThem() {
        // The car's update check really did arrive in five separate pieces.
        val s = session()
        val parts = listOf(
            "00 00 80 3D 00 00 00 04 00 00 00 00",
            "00 00 15 21",
            "00 00 CB 70",
            "00 00 00 0B 53 44 30 39 2E 35 2E 30 2E 31 32",
            "00 00 00 0C 6E 65 74 2E 65 61 73 79 63 6F 6E 6E",
        )
        val replies = parts.flatMap { s.feed(hex(it)) }
        assertEquals(1, replies.size)
        assertArrayEquals(hex("00 00 00 08") + "noupdate".toByteArray(), replies[0])
    }

    @Test
    fun answersUsingCarmanWithZero() {
        // The car says which app it uses: a name, then four numbers.
        val bytes = hex("00 00 80 31 00 00 00 04 00 00 00 17") +
            "net.easyconn.carman.wws".toByteArray() +
            hex("00 00 00 00 00 00 15 21 00 00 00 20 00 00 00 00")
        val replies = session().feed(bytes)
        assertArrayEquals(hex("00 00 00 00"), replies.single())
        assertTrue(logs.any { it.contains("package=net.easyconn.carman.wws") && it.contains("5409") })
    }

    @Test
    fun answersHttpRelayWithRequestErrorAndMakesNoRequest() {
        val url = "/index.php?q=abc"
        val bytes = hex("00 00 00 08") + hex("00 00 00 10") + url.toByteArray()
        val replies = session().feed(bytes)
        assertArrayEquals(hex("00 00 00 0E") + "REQUEST ERROR!".toByteArray(), replies.single())
    }

    @Test
    fun unknownCommandIsLoggedAndIgnoredAfterwards() {
        val s = session()
        assertTrue(s.feed(hex("00 00 FF FF 01 02 03")).isEmpty())
        assertTrue(logs.any { it.contains("UNKNOWN") && it.contains("01 02 03") })
        // After an unknown command the rest of the connection is ignored, even known commands.
        assertTrue(s.feed(hex("00 00 00 09")).isEmpty())
    }

    @Test
    fun twoCommandsInOneReadAreBothAnswered() {
        val replies = session().feed(hex("00 00 00 09 00 00 80 01"))
        assertEquals(2, replies.size)
    }
}
