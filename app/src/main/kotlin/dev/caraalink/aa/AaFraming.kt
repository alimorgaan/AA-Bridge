package dev.caraalink.aa

/**
 * Android Auto's frame/message codec — the layer *below* the protocol and
 * *above* the socket. Everything here is taken from `opencardev/aasdk`
 * (branch `newdev`), the same public GPL source the head-unit identity in
 * `assets/aa/` came from. No guesses:
 *
 * - `src/Messenger/FrameHeader.cpp` — 2-byte header:
 *     byte 0 = channel id
 *     byte 1 = encryption | message type | frame type
 *   read back as `b1 & 0x03` (frame), `b1 & 0x04` (message),
 *   `b1 & 0x08` (encryption).
 * - `src/Messenger/FrameSize.cpp` — SHORT is `uint16` big-endian frame size;
 *   EXTENDED (FIRST frames only) adds a `uint32` big-endian *total* size.
 * - `src/Messenger/MessageId.cpp` — message id is a `uint16` big-endian.
 * - `src/Channel/Control/ControlServiceChannel.cpp::sendVersionRequest` —
 *   control message payload is message id followed by the body.
 *
 * A *message* is one or more frames sharing a channel; the payloads
 * concatenate. Only single-frame (BULK) messages are produced here, which is
 * all the version exchange needs.
 */
object AaFraming {

    // ChannelId — order matters, it is the enum's ordinal (ChannelId.hpp).
    const val CHANNEL_CONTROL = 0
    const val CHANNEL_SENSOR = 1
    const val CHANNEL_MEDIA_SINK = 2
    const val CHANNEL_MEDIA_SINK_VIDEO = 3
    const val CHANNEL_INPUT_SOURCE = 8

    // FrameType.hpp
    const val FRAME_MIDDLE = 0x00
    const val FRAME_FIRST = 0x01
    const val FRAME_LAST = 0x02
    const val FRAME_BULK = 0x03 // FIRST | LAST

    // MessageType.hpp
    const val MESSAGE_SPECIFIC = 0x00
    const val MESSAGE_CONTROL = 0x04

    // EncryptionType.hpp
    const val ENCRYPTION_PLAIN = 0x00
    const val ENCRYPTION_ENCRYPTED = 0x08

    // ControlMessageType.proto
    const val MSG_VERSION_REQUEST = 1
    const val MSG_VERSION_RESPONSE = 2
    const val MSG_ENCAPSULATED_SSL = 3
    const val MSG_AUTH_COMPLETE = 4
    const val MSG_SERVICE_DISCOVERY_REQUEST = 5
    const val MSG_SERVICE_DISCOVERY_RESPONSE = 6
    const val MSG_CHANNEL_OPEN_REQUEST = 7
    const val MSG_CHANNEL_OPEN_RESPONSE = 8
    const val MSG_PING_REQUEST = 11
    const val MSG_PING_RESPONSE = 12
    const val MSG_NAV_FOCUS_REQUEST = 13
    const val MSG_NAV_FOCUS_NOTIFICATION = 14
    const val MSG_AUDIO_FOCUS_REQUEST = 18
    const val MSG_AUDIO_FOCUS_NOTIFICATION = 19
    const val MSG_SHUTDOWN_REQUEST = 15
    const val MSG_SHUTDOWN_RESPONSE = 16

    // MediaMessageId.proto (media sink/source channels)
    const val MSG_MEDIA_DATA = 0
    // Collides numerically with MSG_VERSION_REQUEST (both 1) -- only valid on
    // a media sink/source channel, never on CONTROL. aasdk's messageHandler()
    // routes this straight to onMediaIndication(payload) with NO timestamp
    // prefix (unlike MEDIA_MESSAGE_DATA) -- it's the SPS/PPS codec config,
    // sent once before the first MEDIA_MESSAGE_DATA frame.
    const val MSG_MEDIA_CODEC_CONFIG = 1
    const val MSG_MEDIA_SETUP = 32768
    const val MSG_MEDIA_START = 32769
    const val MSG_MEDIA_STOP = 32770
    const val MSG_MEDIA_CONFIG = 32771
    const val MSG_MEDIA_VIDEO_FOCUS_REQUEST = 32775
    const val MSG_MEDIA_VIDEO_FOCUS_NOTIFICATION = 32776
    const val MSG_AV_MEDIA_ACK = 32772
    const val MSG_MEDIA_MICROPHONE_REQUEST = 32773
    // Quirk, cited from aasdk's MediaSourceService::sendMicrophoneOpenResponse():
    // the response re-sends the REQUEST id, there is no separate response id in
    // practice even though MediaMessageId.proto defines 32774.

    // SensorMessageId.proto (sensor channel)
    const val MSG_SENSOR_REQUEST = 32769
    const val MSG_SENSOR_RESPONSE = 32770
    const val MSG_SENSOR_EVENT = 32771

    // InputMessageId.proto (input channel)
    const val MSG_INPUT_REPORT = 32769
    const val MSG_INPUT_BINDING_REQUEST = 32770
    const val MSG_INPUT_BINDING_RESPONSE = 32771

    /** include/aasdk/Version.hpp — what aasdk advertises to the gearhead. */
    const val PROTOCOL_MAJOR = 1
    const val PROTOCOL_MINOR = 6

    data class Frame(
        val channelId: Int,
        val frameType: Int,
        val messageType: Int,
        val encryption: Int,
        val frameSize: Int,
        val totalSize: Int,
        val payload: ByteArray,
    ) {
        val isFirst: Boolean get() = frameType and FRAME_FIRST != 0
        val isLast: Boolean get() = frameType and FRAME_LAST != 0
        val isPlain: Boolean get() = encryption == ENCRYPTION_PLAIN
    }

    /** Encodes one frame around [payload]. */
    fun frame(
        channelId: Int,
        frameType: Int,
        encryption: Int,
        messageType: Int,
        payload: ByteArray,
        totalSize: Int = payload.size,
    ): ByteArray {
        val sizeField = if (frameType == FRAME_FIRST) 6 else 2
        val out = ByteArray(2 + sizeField + payload.size)
        out[0] = channelId.toByte()
        out[1] = (encryption or messageType or frameType).toByte()
        putU16(out, 2, payload.size)
        if (sizeField == 6) putU32(out, 4, totalSize)
        payload.copyInto(out, 2 + sizeField)
        return out
    }

    /** Control-channel message: `uint16` big-endian [messageId] + [body]. */
    fun controlMessage(messageId: Int, body: ByteArray = ByteArray(0), encryption: Int = ENCRYPTION_PLAIN): ByteArray {
        val payload = ByteArray(2 + body.size)
        putU16(payload, 0, messageId)
        body.copyInto(payload, 2)
        return frame(CHANNEL_CONTROL, FRAME_BULK, encryption, MESSAGE_SPECIFIC, payload)
    }

    /**
     * The version request, byte for byte as aasdk builds it: message id 1,
     * then major/minor as two big-endian uint16s.
     *
     * With the defaults this is `00 03 00 06 00 01 00 01 00 06`.
     */
    fun versionRequest(
        major: Int = PROTOCOL_MAJOR,
        minor: Int = PROTOCOL_MINOR,
    ): ByteArray {
        val body = ByteArray(4)
        putU16(body, 0, major)
        putU16(body, 2, minor)
        return controlMessage(MSG_VERSION_REQUEST, body)
    }

    fun putU16(dst: ByteArray, offset: Int, value: Int) {
        dst[offset] = (value shr 8).toByte()
        dst[offset + 1] = value.toByte()
    }

    fun putU32(dst: ByteArray, offset: Int, value: Int) {
        dst[offset] = (value shr 24).toByte()
        dst[offset + 1] = (value shr 16).toByte()
        dst[offset + 2] = (value shr 8).toByte()
        dst[offset + 3] = value.toByte()
    }

    fun u16(src: ByteArray, offset: Int): Int =
        ((src[offset].toInt() and 0xFF) shl 8) or (src[offset + 1].toInt() and 0xFF)

    fun u32(src: ByteArray, offset: Int): Int =
        ((src[offset].toInt() and 0xFF) shl 24) or
            ((src[offset + 1].toInt() and 0xFF) shl 16) or
            ((src[offset + 2].toInt() and 0xFF) shl 8) or
            (src[offset + 3].toInt() and 0xFF)

    /** Message id of a control-channel payload, or null if it is too short. */
    fun controlMessageId(payload: ByteArray): Int? =
        if (payload.size >= 2) u16(payload, 0) else null

    fun channelName(id: Int): String = when (id) {
        CHANNEL_CONTROL -> "CONTROL"
        CHANNEL_SENSOR -> "SENSOR"
        CHANNEL_MEDIA_SINK -> "MEDIA_SINK"
        CHANNEL_MEDIA_SINK_VIDEO -> "MEDIA_SINK_VIDEO"
        CHANNEL_INPUT_SOURCE -> "INPUT_SOURCE"
        else -> "ch$id"
    }

    fun controlMessageName(id: Int): String = when (id) {
        MSG_VERSION_REQUEST -> "VERSION_REQUEST"
        MSG_VERSION_RESPONSE -> "VERSION_RESPONSE"
        MSG_ENCAPSULATED_SSL -> "ENCAPSULATED_SSL"
        MSG_SERVICE_DISCOVERY_REQUEST -> "SERVICE_DISCOVERY_REQUEST"
        MSG_SERVICE_DISCOVERY_RESPONSE -> "SERVICE_DISCOVERY_RESPONSE"
        MSG_PING_REQUEST -> "PING_REQUEST"
        MSG_PING_RESPONSE -> "PING_RESPONSE"
        MSG_NAV_FOCUS_REQUEST -> "NAV_FOCUS_REQUEST"
        MSG_NAV_FOCUS_NOTIFICATION -> "NAV_FOCUS_NOTIFICATION"
        MSG_CHANNEL_OPEN_REQUEST -> "CHANNEL_OPEN_REQUEST"
        MSG_CHANNEL_OPEN_RESPONSE -> "CHANNEL_OPEN_RESPONSE"
        MSG_AUDIO_FOCUS_REQUEST -> "AUDIO_FOCUS_REQUEST"
        MSG_AUDIO_FOCUS_NOTIFICATION -> "AUDIO_FOCUS_NOTIFICATION"
        MSG_SHUTDOWN_REQUEST -> "SHUTDOWN_REQUEST"
        MSG_SHUTDOWN_RESPONSE -> "SHUTDOWN_RESPONSE"
        MSG_MEDIA_DATA -> "MEDIA_DATA"
        MSG_MEDIA_SETUP -> "MEDIA_SETUP"
        MSG_MEDIA_START -> "MEDIA_START/SENSOR_REQUEST" // same numeric id, different channel namespace
        MSG_MEDIA_STOP -> "MEDIA_STOP"
        MSG_MEDIA_CONFIG -> "MEDIA_CONFIG"
        MSG_MEDIA_VIDEO_FOCUS_REQUEST -> "MEDIA_VIDEO_FOCUS_REQUEST"
        MSG_MEDIA_VIDEO_FOCUS_NOTIFICATION -> "MEDIA_VIDEO_FOCUS_NOTIFICATION"
        MSG_SENSOR_RESPONSE -> "SENSOR_RESPONSE"
        MSG_AV_MEDIA_ACK -> "AV_MEDIA_ACK"
        else -> "msg#$id"
    }

    /** "FIRST|LAST", "BULK", ... for logs. */
    fun frameTypeName(t: Int): String = when (t) {
        FRAME_BULK -> "BULK"
        FRAME_FIRST -> "FIRST"
        FRAME_LAST -> "LAST"
        FRAME_MIDDLE -> "MIDDLE"
        else -> "frame$t"
    }
}
