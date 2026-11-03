package dev.velum.ble.wifi

import android.net.Network
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Phone-side **WebSocket** server on **:8081** — Plaud APK parity (`org.java_websocket`).
 *
 * Prefer [bindHost] = SoftAP link address from [AndroidWifiSoftApJoiner.softApHostAddress].
 * When a SoftAP [Network] is provided, [Network.bindSocket] is attempted on each accepted client
 * socket (socket-level only — never process-wide bind). On real Android that call is expected to
 * throw for an already connected socket, so it is best effort and failures go to [onBindFailure];
 * see docs/protocol/WIFI_FAST_PATH.md.
 */
class AndroidWifiTransferServer(
    private val softApNetwork: () -> Network? = { null },
    /** Called when binding an accepted socket to the SoftAP network fails (class only, never data). */
    private val onBindFailure: (Throwable) -> Unit = { Log.w(TAG, "SoftAP socket bind failed (${it::class.simpleName})") },
) : WifiTransferServer {
    private val incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    private val running = AtomicBoolean(false)
    private val serverRef = AtomicReference<WebSocketServer?>(null)
    private val clientRef = AtomicReference<WebSocket?>(null)
    private val expectedPreamble = AtomicReference(WifiTransferPreamble.defaultToken())

    override suspend fun start(
        port: Int,
        bindHost: String?,
        preamble: ByteArray,
    ) {
        stop()
        expectedPreamble.set(preamble)
        val address = if (bindHost != null) {
            InetSocketAddress(bindHost, port)
        } else {
            InetSocketAddress(port)
        }
        val network = softApNetwork()
        val server = object : WebSocketServer(address) {
            override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
                bindClientToSoftAp(conn, network)
                // Authorization is per connection: the peer is only recorded after its own preamble.
            }

            override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
                clientRef.compareAndSet(conn, null)
            }

            override fun onMessage(conn: WebSocket, message: String) = Unit

            override fun onMessage(conn: WebSocket, message: ByteBuffer) {
                val bytes = ByteArray(message.remaining())
                message.get(bytes)
                // The single authenticated peer is clientRef; any other connection must send its own preamble.
                // Latest authenticated wins: a device reconnect must not be locked out by a stale socket.
                if (clientRef.get() !== conn) {
                    if (WifiTransferPreamble.matches(bytes, expectedPreamble.get())) {
                        clientRef.getAndSet(conn)?.let { old -> runCatching { old.close(1001, "superseded") } }
                    } else {
                        conn.close(1008, "preamble_mismatch")
                    }
                    return
                }
                if (bytes.size > MAX_FRAME_BYTES) {
                    conn.close(1009, "frame_too_large")
                    return
                }
                incoming.tryEmit(bytes)
            }

            override fun onError(conn: WebSocket?, ex: Exception) = Unit

            override fun onStart() {
                running.set(true)
            }
        }
        serverRef.set(server)
        server.isReuseAddr = true
        server.start()
        var spins = 0
        while (!running.get() && spins < 50) {
            Thread.sleep(10)
            spins++
        }
    }

    override suspend fun stop() {
        running.set(false)
        clientRef.getAndSet(null)?.let { runCatching { it.close() } }
        serverRef.getAndSet(null)?.let { s -> runCatching { s.stop(500) } }
    }

    override fun incomingEncryptedFrames(): Flow<ByteArray> = incoming.asSharedFlow()

    override suspend fun sendEncryptedFrame(bytes: ByteArray) {
        val client = clientRef.get()
            ?: error("No authenticated Wi-Fi WebSocket client on :${WifiTransferServer.DEFAULT_PORT}")
        require(bytes.size <= MAX_FRAME_BYTES) { "Frame exceeds $MAX_FRAME_BYTES" }
        synchronized(client) { client.send(bytes) }
    }

    private fun bindClientToSoftAp(conn: WebSocket, network: Network?) {
        if (network == null) return
        try {
            val method = conn.javaClass.methods.firstOrNull {
                it.name == "getChannel" && it.parameterCount == 0
            }
            val channel = method?.invoke(conn) as? java.nio.channels.ByteChannel
            val socket = (channel as? java.nio.channels.SocketChannel)?.socket()
            if (socket == null) {
                onBindFailure(IllegalStateException("accepted socket not reachable"))
            } else {
                network.bindSocket(socket)
            }
        } catch (t: Throwable) {
            onBindFailure(t)
        }
    }

    companion object {
        private const val TAG = "AndroidWifiServer"
        const val MAX_FRAME_BYTES: Int = 8 * 1024 * 1024
    }
}
