package dev.velum.ble.wifi

import android.net.Network
import android.util.Log
import kotlinx.coroutines.delay
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
    private val failures = MutableSharedFlow<String>(extraBufferCapacity = 8)
    private val running = AtomicBoolean(false)
    private val bindFailed = AtomicBoolean(false)
    private val serverRef = AtomicReference<WebSocketServer?>(null)
    private val clientRef = AtomicReference<WebSocket?>(null)
    private val expectedPreamble = AtomicReference(WifiTransferPreamble.defaultToken())

    override suspend fun start(
        port: Int,
        bindHost: String?,
        preamble: ByteArray,
    ) {
        stop()
        bindFailed.set(false)
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
                // Only the authenticated peer counts; a superseded or unauthenticated close changes nothing.
                if (clientRef.compareAndSet(conn, null) && running.get()) {
                    val queued = incoming.subscriptionCount.value > 0 && incoming.tryEmit(WifiTransferServer.PEER_CLOSED_FRAME)
                    if (!queued) failures.tryEmit(WifiTransferServer.FAILURE_PEER_CLOSED)
                }
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
                if (!incoming.tryEmit(bytes)) {
                    // The consumer fell behind: a frame is lost, so the sequence has a gap. Fail the path.
                    failures.tryEmit(WifiTransferServer.FAILURE_FRAME_OVERFLOW)
                    conn.close(1013, "overloaded")
                }
            }

            override fun onError(conn: WebSocket?, ex: Exception) {
                // A null connection before onStart means the listener never came up, such as a port in use.
                if (conn == null && !running.get()) {
                    Log.w(TAG, "Wi-Fi transfer listener failed to start (${ex::class.simpleName})")
                    bindFailed.set(true)
                }
            }

            override fun onStart() {
                running.set(true)
            }
        }
        serverRef.set(server)
        server.isReuseAddr = true
        server.start()
        // onStart or onError(null) always follows a bind attempt. The limit only ends a wait that went wrong.
        var waits = 0
        while (!running.get() && !bindFailed.get() && waits < BIND_MAX_WAITS) {
            delay(BIND_WAIT_MS)
            waits++
        }
        if (!running.get()) {
            stop()
            throw WifiTransferFailure(BIND_FAILURE_CODE)
        }
    }

    override suspend fun stop() {
        running.set(false)
        clientRef.getAndSet(null)?.let { runCatching { it.close() } }
        serverRef.getAndSet(null)?.let { s -> runCatching { s.stop(500) } }
    }

    override fun incomingEncryptedFrames(): Flow<ByteArray> = incoming.asSharedFlow()

    override fun pathFailures(): Flow<String> = failures.asSharedFlow()

    override suspend fun sendEncryptedFrame(bytes: ByteArray) {
        val client = clientRef.get()
            ?: error("No authenticated Wi-Fi WebSocket client on :${WifiTransferServer.DEFAULT_PORT}")
        require(bytes.size <= MAX_FRAME_BYTES) { "Frame exceeds $MAX_FRAME_BYTES" }
        synchronized(client) { client.send(bytes) }
    }

    private fun bindClientToSoftAp(conn: WebSocket, network: Network?) {
        if (network == null) return
        // Report outside the try so a throwing callback is never invoked twice.
        val failure: Throwable? = try {
            val method = conn.javaClass.methods.firstOrNull {
                it.name == "getChannel" && it.parameterCount == 0
            }
            val channel = method?.invoke(conn) as? java.nio.channels.ByteChannel
            val socket = (channel as? java.nio.channels.SocketChannel)?.socket()
            if (socket == null) {
                IllegalStateException("accepted socket not reachable")
            } else {
                network.bindSocket(socket)
                null
            }
        } catch (t: Throwable) {
            t
        }
        if (failure != null) onBindFailure(failure)
    }

    companion object {
        private const val TAG = "AndroidWifiServer"
        const val MAX_FRAME_BYTES: Int = 8 * 1024 * 1024
        const val BIND_FAILURE_CODE: String = "wifi_bind_failed"
        private const val BIND_WAIT_MS = 10L
        private const val BIND_MAX_WAITS = 500
    }
}
