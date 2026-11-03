package dev.velum.ble.wifi

import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The fake device's close must not wait for a peer that never completes the close handshake. The library's
 * closeBlocking() has no timeout and can wait about 120 s (60 s tick, 90 s pong threshold, no ping while closing);
 * a stuck close like that is the suspected, not reproduced, cause of a CI test reaching its 90 s bound.
 *
 * The peer here completes the WebSocket upgrade by hand and then sends nothing. It reads until the socket is
 * closed and counts a latch down only on end-of-stream, so the test passes only when the client ends the
 * connection itself (the hard close after the bound).
 */
class FakeWifiDeviceClientCloseTest {
    private fun silentPeerAfterUpgrade(server: ServerSocket, sawEof: CountDownLatch) = thread(isDaemon = true) {
        runCatching {
            server.accept().use { socket ->
                socket.soTimeout = 10_000
                val input = socket.getInputStream()
                val request = StringBuilder()
                while (!request.endsWith("\r\n\r\n")) {
                    val b = input.read()
                    if (b == -1) return@use
                    request.append(b.toChar())
                }
                val key = Regex("Sec-WebSocket-Key: (.+)\r\n", RegexOption.IGNORE_CASE).find(request)!!.groupValues[1].trim()
                val accept = Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-1").digest("${key}258EAFA5-E914-47DA-95CA-C5AB0DC85B11".toByteArray()),
                )
                socket.getOutputStream().write(
                    ("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                        "Sec-WebSocket-Accept: $accept\r\n\r\n").toByteArray(),
                )
                socket.getOutputStream().flush()
                // Reads (and ignores) the client's close frame, sends nothing back, and waits for the client to hang up.
                while (true) {
                    if (input.read() == -1) {
                        sawEof.countDown()
                        return@use
                    }
                }
            }
        }
    }

    /** One retry, so a loaded machine does not fail the test on a slow first connect. */
    private fun connected(port: Int): FakeWifiDeviceClient {
        repeat(2) {
            val device = FakeWifiDeviceClient(port = port)
            if (runCatching { device.connect(timeoutMs = 10_000) }.isSuccess) return device
            device.close(timeoutMs = 100)
        }
        return FakeWifiDeviceClient(port = port).also { it.connect(timeoutMs = 10_000) }
    }

    @Test
    fun closeEndsTheConnection_whenThePeerNeverCompletesTheCloseHandshake() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val sawEof = CountDownLatch(1)
            silentPeerAfterUpgrade(server, sawEof)
            val device = connected(server.localPort)
            val closer = thread(isDaemon = true) { device.close(timeoutMs = 1_000) }
            assertTrue(sawEof.await(8, TimeUnit.SECONDS), "the client never hung up on a peer that ignored its close")
            closer.join(5_000)
            assertFalse(closer.isAlive, "close() did not return")
        }
    }
}
