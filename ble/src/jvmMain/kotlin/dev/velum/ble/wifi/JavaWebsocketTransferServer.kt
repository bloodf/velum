package dev.velum.ble.wifi

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
 * Phone-side WebSocket server on :8081 — Plaud APK parity (`org.java_websocket`).
 *
 * Used on JVM (fake SoftAP E2E) and as the core for Android (optionally bound to
 * SoftAP [android.net.Network] via socket factory before accept).
 */
class JavaWebsocketTransferServer : WifiTransferServer {
    private val incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    private val failures = MutableSharedFlow<String>(extraBufferCapacity = 8)
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
        val host = bindHost ?: "127.0.0.1"
        val address = InetSocketAddress(host, port)
        val server = object : WebSocketServer(address) {
            override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
                // Authorization is per connection: the peer is only recorded after its own preamble.
            }

            override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
                // Only the authenticated peer counts; a superseded or unauthenticated close changes nothing.
                if (clientRef.compareAndSet(conn, null) && running.get()) {
                    failures.tryEmit(WifiTransferServer.FAILURE_PEER_CLOSED)
                }
            }

            override fun onMessage(conn: WebSocket, message: String) {
                // Plaud transfer is binary-only; ignore text.
            }

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
                if (!incoming.tryEmit(bytes)) {
                    // The consumer fell behind: a frame is lost, so the sequence has a gap. Fail the path.
                    failures.tryEmit(WifiTransferServer.FAILURE_FRAME_OVERFLOW)
                    conn.close(1013, "overloaded")
                }
            }

            override fun onError(conn: WebSocket?, ex: Exception) {
                // Closed during stop() or accept cancelled.
            }

            override fun onStart() {
                running.set(true)
            }
        }
        serverRef.set(server)
        server.isReuseAddr = true
        server.start()
        // Brief wait for bind
        var spins = 0
        while (!running.get() && spins < 50) {
            Thread.sleep(10)
            spins++
        }
    }

    override suspend fun stop() {
        running.set(false)
        clientRef.getAndSet(null)?.let { runCatching { it.close() } }
        serverRef.getAndSet(null)?.let { s ->
            runCatching { s.stop(500) }
        }
    }

    override fun incomingEncryptedFrames(): Flow<ByteArray> = incoming.asSharedFlow()

    override fun pathFailures(): Flow<String> = failures.asSharedFlow()

    override suspend fun sendEncryptedFrame(bytes: ByteArray) {
        val client = clientRef.get()
            ?: error("No authenticated Wi-Fi WebSocket client on :${WifiTransferServer.DEFAULT_PORT}")
        require(bytes.size <= MAX_FRAME_BYTES) { "Frame exceeds $MAX_FRAME_BYTES" }
        synchronized(client) {
            client.send(bytes)
        }
    }

    companion object {
        const val MAX_FRAME_BYTES: Int = 8 * 1024 * 1024
    }
}
