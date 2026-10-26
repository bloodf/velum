package dev.velum.ble.wifi

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Minimal phone-side TCP listener on **:8081** for Wi-Fi fast transfer.
 *
 * Skeleton: accepts one client and relays length-prefixed blobs. The official
 * app uses WebSocket; a WS library can replace this later. Bind is local only —
 * never contacts Plaud servers.
 */
class AndroidWifiTransferServer : WifiTransferServer {
    private val incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    private val running = AtomicBoolean(false)
    private val serverRef = AtomicReference<ServerSocket?>(null)
    private val clientOut = AtomicReference<OutputStream?>(null)

    override suspend fun start(port: Int) {
        stop()
        val server = ServerSocket()
        server.reuseAddress = true
        server.bind(InetSocketAddress(port))
        serverRef.set(server)
        running.set(true)
        thread(name = "velum-wifi-transfer", isDaemon = true) {
            try {
                val client = server.accept()
                clientOut.set(client.getOutputStream())
                val input = client.getInputStream()
                while (running.get()) {
                    val len = readInt(input) ?: break
                    if (len <= 0 || len > 8 * 1024 * 1024) break
                    val body = readExact(input, len) ?: break
                    incoming.tryEmit(body)
                }
            } catch (_: Exception) {
                // Closed during stop() or accept cancelled.
            }
        }
    }

    override suspend fun stop() {
        running.set(false)
        clientOut.getAndSet(null)?.let { runCatching { it.close() } }
        serverRef.getAndSet(null)?.let { runCatching { it.close() } }
    }

    override fun incomingEncryptedFrames(): Flow<ByteArray> = incoming.asSharedFlow()

    override suspend fun sendEncryptedFrame(bytes: ByteArray) {
        val out = clientOut.get()
            ?: error("No Wi-Fi transfer client connected on :${WifiTransferServer.DEFAULT_PORT}")
        synchronized(out) {
            writeInt(out, bytes.size)
            out.write(bytes)
            out.flush()
        }
    }

    private fun readExact(input: InputStream, len: Int): ByteArray? {
        val body = ByteArray(len)
        var offset = 0
        while (offset < len) {
            val n = input.read(body, offset, len - offset)
            if (n < 0) return null
            offset += n
        }
        return body
    }

    private fun readInt(input: InputStream): Int? {
        val b = readExact(input, 4) ?: return null
        return ((b[0].toInt() and 0xFF) shl 24) or
            ((b[1].toInt() and 0xFF) shl 16) or
            ((b[2].toInt() and 0xFF) shl 8) or
            (b[3].toInt() and 0xFF)
    }

    private fun writeInt(out: OutputStream, value: Int) {
        out.write(
            byteArrayOf(
                (value ushr 24).toByte(),
                (value ushr 16).toByte(),
                (value ushr 8).toByte(),
                value.toByte(),
            ),
        )
    }
}
