package dev.caraalink.wireless

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** CarbitLink's ECP_PXC_MDNS_RESPOND on port 10930: tells the car to connect back to the phone. */
object CarHello {
    const val PORT = 10930
    const val REQUEST = 0x70000010
    const val ANSWER = 0x70000011
    const val CARBITLINK_PACKAGE = "net.easyconn.carman.wws"

    fun packet(packageName: String): ByteArray {
        // Package names are [A-Za-z0-9._] only, so no JSON escaping is needed.
        val body = """{"phoneType":"Android","packageName":"$packageName"}""".toByteArray()
        val total = 16 + body.size
        return ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(REQUEST).putInt(total).putInt(REQUEST xor total).putInt(0).put(body).array()
    }

    /** The command of a reply, or null when fewer than the 16 header bytes arrived. */
    fun answerCommand(reply: ByteArray, length: Int): Int? =
        if (length < 16) null else ByteBuffer.wrap(reply, 0, 16).order(ByteOrder.LITTLE_ENDIAN).getInt(0)

    /** True when the car answered with [ANSWER]. Retries only a failed connect, 10 times 1 s apart, so a silent car fails fast. */
    fun send(host: String, local: InetAddress?, packageName: String, port: Int = PORT): Boolean {
        val packet = packet(packageName)
        repeat(10) {
            val s = Socket()
            try {
                if (local != null) s.bind(InetSocketAddress(local, 0))
                s.connect(InetSocketAddress(host, port), 3000)
            } catch (e: IOException) {
                runCatching { s.close() }
                Thread.sleep(1000)
                return@repeat
            }
            return s.use {
                runCatching {
                    it.soTimeout = 5000
                    it.getOutputStream().write(packet)
                    val reply = ByteArray(4096)
                    answerCommand(reply, it.getInputStream().read(reply)) == ANSWER
                }.getOrDefault(false)
            }
        }
        return false
    }
}
