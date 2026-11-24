package dev.caraalink.link

import android.content.Context
import dev.caraalink.diagnostics.DiagnosticLogger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/**
 * Headless coverage for tcp:10921 (mirror control): no USB, no AdbTransport
 * -- [FakeStream] stands in for the wire, [FakeMirrorHost] for [CarSession].
 * Part of the Stream-interface extraction from claude/roadmap.md's "Future
 * ideas" headless testing harness.
 */
class MirrorControlChannelTest {

    private lateinit var host: FakeMirrorHost
    private lateinit var channel: MirrorControlChannel
    private lateinit var stream: FakeStream

    @Before
    fun setUp() {
        val context = mock(Context::class.java)
        `when`(context.getExternalFilesDir(null)).thenReturn(createTempDir())
        val logger = DiagnosticLogger(context, "test")
        host = FakeMirrorHost()
        channel = MirrorControlChannel(logger, host)
        stream = FakeStream()
        channel.onOpen(stream)
    }

    @Test
    fun `heartbeat gets an empty 0x41 reply`() {
        channel.onData(stream, req(MirrorControlChannel.REQ_HEARTBEAT, ByteArray(0)))

        assertEquals(1, stream.written.size)
        assertEquals(0x41, msgType(stream.written[0]))
    }

    @Test
    fun `CONFIG_CAPTURE reaches the host parsed, and replies with H264 + encode size`() {
        val body = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(1920).putShort(720).putInt(0).putInt(2).putInt(0)
            .putShort(0).putShort(0).putInt(4_194_304).array()

        channel.onData(stream, req(MirrorControlChannel.REQ_CONFIG_CAPTURE, body))

        assertEquals(1, host.capturedConfigs.size)
        assertEquals(1920, host.capturedConfigs[0].width)
        assertEquals(720, host.capturedConfigs[0].height)

        val reply = stream.written.single()
        assertEquals(0x11, msgType(reply))
        val rb = ByteBuffer.wrap(reply, 8, reply.size - 8).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(MirrorControlChannel.H264, rb.int)
        assertEquals(1920, rb.short.toInt() and 0xFFFF)
        assertEquals(720, rb.short.toInt() and 0xFFFF)
    }

    @Test
    fun `DATA_START asks the host and relays success`() {
        channel.onData(stream, req(MirrorControlChannel.REQ_DATA_START, ByteArray(0)))

        assertEquals(1, host.startVideoCalls)
        assertEquals(0x71, msgType(stream.written.single()))
    }

    @Test
    fun `SCREEN_EVENT down-move-up forward touches with the right action codes`() {
        channel.onData(stream, req(MirrorControlChannel.REQ_SCREEN_EVENT, screenEvent(evType = 2, x = 100, y = 200)))
        channel.onData(stream, req(MirrorControlChannel.REQ_SCREEN_EVENT, screenEvent(evType = 3, x = 110, y = 210)))
        channel.onData(stream, req(MirrorControlChannel.REQ_SCREEN_EVENT, screenEvent(evType = 1, x = 120, y = 220)))

        assertEquals(3, host.touches.size)
        val (downAction, downX, downY) = host.touches[0]
        assertEquals(android.view.MotionEvent.ACTION_DOWN, downAction)
        assertEquals(100, downX); assertEquals(200, downY)
        assertEquals(android.view.MotionEvent.ACTION_MOVE, host.touches[1].first)
        assertEquals(android.view.MotionEvent.ACTION_UP, host.touches[2].first)
    }

    @Test
    fun `SCREEN_EVENT key forwards to onKey, not onTouch`() {
        channel.onData(stream, req(MirrorControlChannel.REQ_SCREEN_EVENT, screenEvent(evType = 4, x = 0, y = 0, key = MirrorControlChannel.KEY_HOME)))

        assertTrue(host.touches.isEmpty())
        assertEquals(listOf(MirrorControlChannel.KEY_HOME), host.keys)
    }

    @Test
    fun `an unknown request type gets RLY_UN_SUPPORT`() {
        channel.onData(stream, req(0x9999, ByteArray(0)))

        val reply = stream.written.single()
        assertEquals(MirrorControlChannel.RLY_UN_SUPPORT, msgType(reply))
    }

    // -- helpers mirroring the private `req`/wire layout in MirrorChannels.kt --

    private fun req(type: Int, body: ByteArray): ByteArray =
        ByteBuffer.allocate(8 + body.size).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(type.toShort()).putShort(body.size.toShort()).putInt(0).put(body).array()

    private fun msgType(frame: ByteArray): Int =
        ByteBuffer.wrap(frame, 0, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF

    private fun screenEvent(evType: Int, x: Int, y: Int, key: Int = 0): ByteArray =
        ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(evType.toShort()).putShort(x.toShort()).putShort(y.toShort())
            .putShort(0) // slot
            .putShort(key.toShort()).array()
}
