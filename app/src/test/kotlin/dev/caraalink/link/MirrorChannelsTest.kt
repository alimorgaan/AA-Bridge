package dev.caraalink.link

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM tests for the CarbitLink mirror channel's wire-level structs --
 * no device, no `AdbTransport`/`UsbAccessory` needed. Start of the headless
 * testing harness from claude/roadmap.md's "Future ideas": regressions in
 * these byte layouts should fail here, not only live against a car.
 *
 * `MirrorControlChannel`/`MirrorDataChannel` themselves aren't covered yet --
 * their `Stream` type is an `AdbTransport` inner class tied to a real
 * connection, so testing them headlessly needs a `Stream`-like interface
 * extracted first (a real but separate piece of work, not done here to
 * avoid a rushed refactor of confirmed-working production code).
 */
class MirrorChannelsTest {

    @Test
    fun `CaptureConfig parse matches this exact car's live CONFIG_CAPTURE`() {
        // Confirmed live (2026-11-02, see claude/handoff.md): this car's
        // head unit reports CaptureConfig(width=1920, height=720, wantFps=0,
        // wantEncoder=2, bitRate=4194304, orientation=1, displayId=0,
        // supportExtendProtocol=0) -- the 2.67:1 panel behind the whole
        // aspect-ratio discussion in roadmap.md. Bytes here are built to
        // match CaptureConfig.parse()'s own documented field layout, pinning
        // that parser against this real confirmed shape rather than a
        // synthetic one.
        val bb = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(1920)   // width
            .putShort(720)    // height
            .putInt(0)        // wantFps
            .putInt(2)        // wantEncoder
            .putInt(0)        // supportCodec (unused by CaptureConfig)
            .putShort(0)      // minQuality
            .putShort(0)      // maxQuality
            .putInt(4_194_304) // bitRate
        val base = bb.array()
        // The real car sends an 8-byte tail (32-byte body); parse() reads only the first 6.
        val extended = base + byteArrayOf(0, 0, 1 /* orientation */, 0 /* displayId */, 0, 0 /* supportExtendProtocol */, 0, 0)

        val config = CaptureConfig.parse(extended)

        assertEquals(1920, config.width)
        assertEquals(720, config.height)
        assertEquals(0, config.wantFps)
        assertEquals(2, config.wantEncoder)
        assertEquals(4_194_304, config.bitRate)
        assertEquals(1, config.orientation)
        assertEquals(0, config.displayId)
        assertEquals(0, config.supportExtendProtocol)
        // encodeWidth/encodeHeight mask off the low 4 bits -- both inputs
        // here are already 16-aligned, so they should pass through unchanged.
        assertEquals(1920, config.encodeWidth)
        assertEquals(720, config.encodeHeight)
    }

    @Test
    fun `CaptureConfig without the optional tail defaults orientation and displayId to 0`() {
        val base = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(1280).putShort(720).putInt(30).putInt(1).putInt(0)
            .putShort(0).putShort(0).putInt(3_000_000)
            .array()

        val config = CaptureConfig.parse(base)

        assertEquals(1280, config.width)
        assertEquals(720, config.height)
        assertEquals(0, config.orientation)
        assertEquals(0, config.displayId)
        assertEquals(0, config.supportExtendProtocol)
    }

    @Test
    fun `MirrorDataChannel frame prefixes a little-endian 4-byte length`() {
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val framed = MirrorDataChannel.frame(payload)

        assertEquals(9, framed.size)
        val length = ByteBuffer.wrap(framed, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
        assertEquals(5, length)
        assertEquals(payload.toList(), framed.copyOfRange(4, 9).toList())
    }

    @Test
    fun `MirrorDataChannel NO_FRAME is a zeroed 4-byte length`() {
        assertEquals(4, MirrorDataChannel.NO_FRAME.size)
        assertEquals(0, ByteBuffer.wrap(MirrorDataChannel.NO_FRAME).order(ByteOrder.LITTLE_ENDIAN).int)
    }
}
