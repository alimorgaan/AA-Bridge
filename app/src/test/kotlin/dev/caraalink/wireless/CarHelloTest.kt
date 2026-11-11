package dev.caraalink.wireless

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class CarHelloTest {
    @Test
    fun packetMatchesCarbitLinkHeader() {
        val body = """{"phoneType":"Android","packageName":"io.github.alimorgaan.aabridge"}"""
        val p = CarHello.packet("io.github.alimorgaan.aabridge")
        val bb = ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x70000010, bb.getInt(0))
        assertEquals(16 + body.length, bb.getInt(4))
        assertEquals(0x70000010 xor (16 + body.length), bb.getInt(8))
        assertEquals(0, bb.getInt(12))
        assertEquals(body, String(p, 16, p.size - 16))
    }

    @Test
    fun answerCommandReadsTheHeader() {
        val reply = byteArrayOf(0x11, 0, 0, 0x70, 0x10, 0, 0, 0, 0x01, 0, 0, 0x70, 0, 0, 0, 0)
        assertEquals(0x70000011, CarHello.answerCommand(reply, 16))
        assertNull(CarHello.answerCommand(reply, 15))
    }
}
