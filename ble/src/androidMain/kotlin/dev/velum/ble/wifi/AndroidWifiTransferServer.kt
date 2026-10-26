package dev.velum.ble.wifi

import android.net.Network
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Phone-side length-prefixed TCP listener on :8081 for SoftAP fast transfer.
 *
 * SoftAP routing is per-socket only (never bindProcessToNetwork):
 * - Listen socket binds the SoftAP interface address when known.
 * - Accepted peers use [Network.bindSocket].
 * - Outbound helpers use [Network.socketFactory] via [SoftApSocketBinder].
 */
class AndroidWifiTransferServer(
    private val softApNetwork: (() -> Network?)? = null,
    private val softApBindHost: (() -> String?)? = null,
) : WifiTransferServer {
    private val incomingRef = java.util.concurrent.atomic.AtomicReference(
        Channel<ByteArray>(capacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST),
    )
    private val running = AtomicBoolean(false)
    private val serverRef = AtomicReference<ServerSocket?>(null)
    private val clientRef = AtomicReference<Socket?>(null)
    private val clientOut = AtomicReference<OutputStream?>(null)
    private val preambleAccepted = AtomicBoolean(false)

    override suspend fun start(
        port: Int,
        bindAddress: String?,
        expectedPreamble: ByteArray,
    ) {
        stop()
        require(expectedPreamble.isNotEmpty()) { "expectedPreamble must be non-empty" }
        preambleAccepted.set(false)
        incomingRef.getAndSet(
            Channel(capacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST),
        ).close()
        val network = softApNetwork?.invoke()
        val host = bindAddress ?: softApBindHost?.invoke() ?: "127.0.0.1"
        if (network != null) {
            require(host != "0.0.0.0") {
                "SoftAP Network known but bind host unresolved; pass SoftAP IPv4"
            }
        }
        val server = ServerSocket()
        server.reuseAddress = true
        server.bind(InetSocketAddress(InetAddress.getByName(host), port))
        serverRef.set(server)
        running.set(true)
        thread(name = "velum-wifi-transfer", isDaemon = true) {
            try {
                val client = server.accept()
                if (network != null) {
                    runCatching { network.bindSocket(client) }
                }
                clientRef.set(client)
                client.soTimeout = 30_000
                val input = client.getInputStream()
                clientOut.set(client.getOutputStream())
                val preamble = readExact(input, expectedPreamble.size) ?: run {
                    client.close()
                    return@thread
                }
                if (!WifiTransferPreamble.matches(preamble, expectedPreamble)) {
                    client.close()
                    return@thread
                }
                preambleAccepted.set(true)
                while (running.get()) {
                    val lenPrefix = readExact(input, WifiTransferFraming.LENGTH_PREFIX_BYTES)
                        ?: break
                    val len = WifiTransferFraming.parseLength(lenPrefix) ?: break
                    val body = readExact(input, len) ?: break
                    incomingRef.get().trySend(body)
                }
            } catch (_: SocketException) {
            } catch (_: Exception) {
            }
        }
    }

    override suspend fun stop() {
        running.set(false)
        preambleAccepted.set(false)
        incomingRef.get().close()
        clientOut.getAndSet(null)
        clientRef.getAndSet(null)?.let { runCatching { it.close() } }
        serverRef.getAndSet(null)?.let { runCatching { it.close() } }
    }

    override fun incomingEncryptedFrames(): Flow<ByteArray> = incomingRef.get().receiveAsFlow()

    override suspend fun sendEncryptedFrame(bytes: ByteArray) {
        require(preambleAccepted.get()) { "Wi-Fi preamble not accepted yet" }
        require(WifiTransferFraming.isValidFrameLength(bytes.size)) {
            "Frame length ${bytes.size} exceeds ${WifiTransferFraming.MAX_FRAME_BYTES}"
        }
        val out = clientOut.get()
            ?: error("No Wi-Fi transfer client on :${WifiTransferServer.DEFAULT_PORT}")
        synchronized(out) {
            out.write(WifiTransferFraming.encodeFrame(bytes))
            out.flush()
        }
    }

    companion object {
        fun readExact(input: InputStream, len: Int): ByteArray? {
            if (len <= 0) return null
            val body = ByteArray(len)
            var offset = 0
            while (offset < len) {
                val n = input.read(body, offset, len - offset)
                if (n < 0) return null
                offset += n
            }
            return body
        }
    }
}
