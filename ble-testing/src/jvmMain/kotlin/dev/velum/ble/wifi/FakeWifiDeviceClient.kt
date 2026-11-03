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
    private var client: FakeClient? = null

    /** Exposes the library's protected hard close so a stuck close handshake can be ended. */
    private class FakeClient(uri: URI, private val handler: Handler) : WebSocketClient(uri) {
        interface Handler {
            fun opened()
            fun failed(ex: Exception?)
        }

        override fun onOpen(handshakedata: ServerHandshake?) = handler.opened()
        override fun onMessage(message: String?) = Unit
        override fun onMessage(bytes: ByteBuffer?) = Unit
        override fun onClose(code: Int, reason: String?, remote: Boolean) = Unit
        override fun onError(ex: Exception?) = handler.failed(ex)
        fun forceClose() = closeConnection(CloseFrame.ABNORMAL_CLOSE, "close handshake timed out")
    }

    fun connect(timeoutMs: Long = 5_000) {
        val uri = URI("ws://$host:$port")
        val c = FakeClient(uri, object : FakeClient.Handler {
            override fun opened() = openLatch.countDown()
            override fun failed(ex: Exception?) {
                errorRef.set(ex)
                openLatch.countDown()
            }
        })
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
     * The close handshake is bounded: the library's `closeBlocking()` has no timeout and only gives up when
     * its 60 s connection-lost timer fires, so a server worker that is slow to answer stalled a whole test
     * (WifiSoftApMockServerTest.frameBeforePreamble_isTreatedAsPreambleAndRejected hit its 90 s bound on CI).
     * After [timeoutMs] the connection is closed hard.
     */
    fun close(timeoutMs: Long = CLOSE_TIMEOUT_MS) {
        val c = client ?: return
        client = null
        runCatching {
            c.close()
            val deadline = System.nanoTime() + timeoutMs * 1_000_000
            while (!c.isClosed && System.nanoTime() < deadline) Thread.sleep(5)
            if (!c.isClosed) c.forceClose()
        }
    }

    private companion object {
        const val CLOSE_TIMEOUT_MS = 5_000L
    }
}
