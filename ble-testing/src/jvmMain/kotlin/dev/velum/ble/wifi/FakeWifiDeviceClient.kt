package dev.velum.ble.wifi

import org.java_websocket.client.WebSocketClient
import org.java_websocket.framing.CloseFrame
import org.java_websocket.handshake.ServerHandshake
import java.net.URI
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Fake Note Pro SoftAP peer: connects to phone WS :8081, sends preamble, then
 * encrypted binary frames (Plaud flow).
 */
class FakeWifiDeviceClient(
    private val host: String = "127.0.0.1",
    private val port: Int = WifiTransferServer.DEFAULT_PORT,
) {
    private val openLatch = CountDownLatch(1)
    private val errorRef = AtomicReference<Exception?>(null)
    private var client: WebSocketClient? = null

    fun connect(timeoutMs: Long = 5_000) {
        val uri = URI("ws://$host:$port")
        val c = object : WebSocketClient(uri) {
            override fun onOpen(handshakedata: ServerHandshake?) {
                openLatch.countDown()
            }

            override fun onMessage(message: String?) = Unit

            override fun onMessage(bytes: ByteBuffer?) = Unit

            override fun onClose(code: Int, reason: String?, remote: Boolean) = Unit

            override fun onError(ex: Exception?) {
                errorRef.set(ex)
                openLatch.countDown()
            }
        }
        client = c
        c.connect()
        check(openLatch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            "WS connect timeout: ${errorRef.get()?.message}"
        }
        errorRef.get()?.let { throw it }
    }

    fun sendPreamble(token: ByteArray = WifiTransferPreamble.defaultToken()) {
        val c = client ?: error("not connected")
        c.send(token)
    }

    fun sendBinary(frame: ByteArray) {
        val c = client ?: error("not connected")
        c.send(frame)
    }

    /**
     * Java-WebSocket's server can drop frames it has read but not yet decoded if it reads this close first
     * (close 1006, #349), so a test that needs every frame through waits for delivery before calling this.
     *
     * The close handshake is bounded: the library's `closeBlocking()` has no timeout, and a close that never
     * completes can wait about 120 s (its lost-connection check ticks every 60 s and fires only when the last
     * pong is older than 90 s; no ping goes out while closing). A stuck close like that is the suspected cause
     * of WifiSoftApMockServerTest.frameBeforePreamble_isTreatedAsPreambleAndRejected reaching its 90 s bound on CI;
     * it was not reproduced.
     * After [timeoutMs] the connection is closed hard.
     */
    fun close(timeoutMs: Long = CLOSE_TIMEOUT_MS) {
        val c = client ?: return
        client = null
        runCatching {
            c.close()
            val deadline = System.nanoTime() + timeoutMs * 1_000_000
            while (!c.isClosed && System.nanoTime() < deadline) Thread.sleep(5)
            if (!c.isClosed) c.closeConnection(CloseFrame.ABNORMAL_CLOSE, "close handshake timed out")
        }
    }

    private companion object {
        const val CLOSE_TIMEOUT_MS = 5_000L
    }
}
