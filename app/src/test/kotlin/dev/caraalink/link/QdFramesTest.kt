package dev.caraalink.link

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class QdFramesTest {
    private fun hex(s: String) = s.split(" ").map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun framesMatchTheProtocolNotes() {
        val hb = QdFrames.json(QdFrames.TYPE_CMD, """{"CMD":"HEARTBEAT"}""")
        assertEquals(512, hb.size)
        assertArrayEquals(hex("35 41 35 41 00 00 00 23 00 00 00 00 00 01 00 00"), hb.copyOf(16))

        val ext = QdFrames.videoExt(1280, 720, 3, 30, 4_000_000, 2)
        val video = QdFrames.frame(QdFrames.TYPE_VIDEO, QdFrames.FORMAT_H264, ByteArray(1000), ext)
        assertEquals(1536, video.size)
        val expected = "35 41 35 41 00 00 04 18 00 20 01 00 00 02 00 00 " +
            "00 20 01 00 00 00 05 00 00 00 02 d0 00 5a 01 03 " +
            "00 00 00 1e 00 3d 09 00 00 00 00 02 01 00 00 00"
        assertArrayEquals(hex(expected), video.copyOf(48))
    }

    @Test
    fun readerHandlesPaddedSplitAndUnpaddedFrames() {
        val got = mutableListOf<Pair<Int, Int>>()
        val junk = mutableListOf<Int>()
        val reader = QdReader({ type, _, payload -> got += type to payload.size }, { _, _ -> }) { junk += it.size }
        val padded = QdFrames.frame(QdFrames.TYPE_VIDEO, QdFrames.FORMAT_H264, ByteArray(700))
        reader.feed(padded.copyOfRange(0, 512), 512)
        reader.feed(padded.copyOfRange(512, 1024), 512)
        val small = QdFrames.json(QdFrames.TYPE_CMD, """{"CMD":"CAR_INFO"}""")
        val unpadded = small.copyOf(16 + 18)
        reader.feed(hex("01 02") + unpadded + unpadded, 4 + 2 * unpadded.size)
        assertEquals(listOf(1 to 700, 0 to 18, 0 to 18), got)
        assertEquals(listOf(2), junk)
    }

    @Test
    fun oldFormatMatchesAGeelyCapture() {
        // The car's own BTADDRESS request carries this CRC.
        assertEquals(0xb83b, QdBin.crc16("A6A6010000003110QDRIVE_ASSISTANT09BTADDRESS00".toByteArray()))
        assertEquals("A6A6000000003110QDRIVE_ASSISTANT09BTADDRESS00569f", String(QdBin.a6a6("BTADDRESS")))
        assertEquals(
            """A6A6000000005110QDRIVE_ASSISTANT0cLEGAL_APP_ON0100000015{"D":0,"T":"i","V":1}f441""",
            String(QdBin.a6a6("LEGAL_APP_ON", """{"D":0,"T":"i","V":1}""")),
        )

        // As the car sent them: a HuMsg block, its A6A6 text in the next 512-byte read, then Version.
        val text = QdBin.a6a6("HUINFO", "x")
        val hu = QdBin.block(QdBin.TYPE_HU, 1, 0, 0).putInt(8, 1024).putInt(64, text.size).array() + text.copyOf(512)
        val version = QdBin.block(QdBin.TYPE_CMD, 1).putInt(68, QdBin.CMD_VERSION).array()
        val got = mutableListOf<String?>()
        val reader = QdReader({ _, _, _ -> }, { b, extra ->
            got += if (b[7].toInt() == QdBin.TYPE_HU) QdBin.logicId(extra.copyOf(text.size)) else "cmd ${b[71]}"
        }) { junk -> throw AssertionError("junk ${junk.size}") }
        reader.feed(hu.copyOf(512), 512)
        reader.feed(hu.copyOfRange(512, 1024) + version, 1024)
        assertEquals(listOf("HUINFO", "cmd 3"), got)
    }

    @Test
    fun watchdogEndsOnlySessionsTheCarStoppedServing() {
        fun w(now: Long, lastRead: Long = now, spoke: Boolean = true, info: Boolean = true, writeSince: Long = 0) =
            QdTransport.watchdog(now, startedAt = 0, lastReadAt = lastRead, carSpoke = spoke, carInfo = info, writeSince = writeSince)
        assertNull(w(60_000, lastRead = 46_000))
        assertNotNull(w(60_000, lastRead = 45_000))
        // A car that never spoke is left to the handshake timeout.
        assertNull(w(60_000, lastRead = 0, spoke = false))
        // A stuck write survives while the car still talks, up to 20 s.
        assertNull(w(60_000, writeSince = 49_000))
        assertNotNull(w(60_000, writeSince = 49_000, lastRead = 54_000))
        assertNotNull(w(60_000, writeSince = 39_000))
        assertNull(w(11_000, info = false))
        assertNotNull(w(12_000, info = false))
    }
}
