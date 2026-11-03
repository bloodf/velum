package dev.velum.ble.wifi

import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.Base64
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertFalse

/**
 * The fake device's close must not wait for a peer that never answers the close handshake. The library's
 * closeBlocking() has no timeout and only ends when its 60 s connection-lost timer fires, which stalled
 * WifiSoftApMockServerTest to its 90 s bound on CI. The peer here completes the WebSocket upgrade by hand
 * and then goes silent: it reads nothing, answers nothing and keeps the socket open.
 */
class FakeWifiDeviceClientCloseTest {
    private fun silentPeerAfterUpgrade(server: ServerSocket) = thread(isDaemon = true) {
        runCatching {
            server.accept().use { socket ->
                val input = socket.getInputStream()
                val request = StringBuilder()
                while (!request.endsWith("\r\n\r\n")) request.append(input.read().toChar())
                val key = Regex("Sec-WebSocket-Key: (.+)\r\n", RegexOption.IGNORE_CASE).find(request)!!.groupValues[1].trim()
                val accept = Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-1").digest("${key}258EAFA5-E914-47DA-95CA-C5AB0DC85B11".toByteArray()),
                )
                socket.getOutputStream().write(
                    ("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                        "Sec-WebSocket-Accept: $accept\r\n\r\n").toByteArray(),
                )
                socket.getOutputStream().flush()
                Thread.sleep(120_000) // never read, never answer
            }
        }
    }

    @Test
    fun closeReturnsPromptly_whenThePeerNeverAnswersTheCloseHandshake() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            silentPeerAfterUpgrade(server)
            val device = FakeWifiDeviceClient(port = server.localPort)
            device.connect(timeoutMs = 10_000)
            val closer = thread(isDaemon = true) { device.close(timeoutMs = 1_000) }
            closer.join(15_000)
            assertFalse(closer.isAlive, "close() is still waiting for the peer after 15 s")
        }
    }
}
