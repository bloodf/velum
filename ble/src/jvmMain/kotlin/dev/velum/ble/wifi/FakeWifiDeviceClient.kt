package dev.velum.ble.wifi

import org.java_websocket.client.WebSocketClient
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

    fun close() {
        runCatching { client?.closeBlocking() }
        client = null
    }
}
