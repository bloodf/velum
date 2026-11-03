package dev.velum.ble.wifi

import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake

/** The preamble gate is per connection: one peer's state must never authorize or revoke another's. */
class PreamblePerConnectionTest {
    private val preamble = WifiTransferPreamble.defaultToken("per-conn")

    private fun freePort(): Int = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }

    private suspend fun withServer(block: suspend (JavaWebsocketTransferServer, Int) -> Unit) {
        val server = JavaWebsocketTransferServer()
        val port = freePort()
        server.start(port, "127.0.0.1", preamble)
        try {
            block(server, port)
        } finally {
            server.stop()
        }
    }

    private suspend fun connect(port: Int) = withContext(Dispatchers.IO) {
        FakeWifiDeviceClient(port = port).also { it.connect() }
    }

    /** Frames the server delivered, recorded by a collector attached before any device sends. */
    private class Recorder(val frames: ConcurrentLinkedQueue<List<Byte>> = ConcurrentLinkedQueue()) {
        suspend fun awaitCount(n: Int) {
            withTimeout(5_000) { while (frames.size < n) delay(10) }
        }

        fun snapshot(): List<List<Byte>> = frames.toList()
    }

    private suspend fun CoroutineScope.record(server: JavaWebsocketTransferServer): Recorder {
        val recorder = Recorder()
        val subscribed = CompletableDeferred<Unit>()
        @Suppress("UNCHECKED_CAST")
        val flow = server.incomingEncryptedFrames() as SharedFlow<ByteArray>
        launch(start = CoroutineStart.UNDISPATCHED) {
            flow.onSubscription { subscribed.complete(Unit) }.collect {
                if (it !== WifiTransferServer.PEER_CLOSED_FRAME) recorder.frames += it.toList()
            }
        }
        subscribed.await()
        return recorder
    }

    private fun run20(block: suspend CoroutineScope.() -> Unit) {
        runBlocking {
            withTimeout(20_000) {
                coroutineScope {
                    block()
                    coroutineContext[Job]!!.cancelChildren() // stop the never-ending frame collectors
                }
            }
        }
    }

    private fun bytes(vararg b: Int) = b.map { it.toByte() }

    @Test
    fun frameWithoutOwnPreamble_isDropped_evenWhenAnotherConnectionAuthenticated() = run20 {
        withServer { server, port ->
            val rec = record(server)
            val a = connect(port)
            val b = connect(port)
            withContext(Dispatchers.IO) {
                a.sendPreamble(preamble)
                a.sendBinary(byteArrayOf(1))
            }
            rec.awaitCount(1)
            withContext(Dispatchers.IO) {
                runCatching { b.sendBinary(byteArrayOf(66)) } // no preamble on B
                b.close()
                a.sendBinary(byteArrayOf(2))
            }
            rec.awaitCount(2)
            delay(300)
            a.close()
            assertEquals(listOf(bytes(1), bytes(2)), rec.snapshot())
        }
    }

    @Test
    fun otherConnectionOpeningAndClosing_doesNotRevokeAuthenticatedPeer() = run20 {
        withServer { server, port ->
            val rec = record(server)
            val a = connect(port)
            withContext(Dispatchers.IO) { a.sendPreamble(preamble) }
            val b = connect(port)
            withContext(Dispatchers.IO) { b.close() }
            // Keep sending across the window in which the server observes B's close; none may be lost.
            repeat(20) { i ->
                withContext(Dispatchers.IO) { a.sendBinary(byteArrayOf(i.toByte())) }
                delay(50)
            }
            rec.awaitCount(20)
            a.close()
            assertEquals((0 until 20).map { bytes(it) }, rec.snapshot())
        }
    }

    @Test
    fun reauthenticationAfterAuthenticatedConnectionCloses_isAccepted() = run20 {
        withServer { server, port ->
            val rec = record(server)
            val a = connect(port)
            withContext(Dispatchers.IO) {
                a.sendPreamble(preamble)
                a.sendBinary(byteArrayOf(1))
            }
            rec.awaitCount(1)
            withContext(Dispatchers.IO) { a.close() }
            val b = connect(port)
            withContext(Dispatchers.IO) {
                b.sendPreamble(preamble)
                b.sendBinary(byteArrayOf(2))
            }
            rec.awaitCount(2)
            b.close()
            assertEquals(listOf(bytes(1), bytes(2)), rec.snapshot())
        }
    }

    @Test
    fun secondValidPreamble_swapsPeer_andOldConnectionIsDropped() = run20 {
        withServer { server, port ->
            val rec = record(server)
            val a = connect(port)
            withContext(Dispatchers.IO) {
                a.sendPreamble(preamble)
                a.sendBinary(byteArrayOf(1))
            }
            rec.awaitCount(1)
            val b = connect(port)
            withContext(Dispatchers.IO) {
                b.sendPreamble(preamble)
                b.sendBinary(byteArrayOf(2))
            }
            rec.awaitCount(2) // B's frame delivered => the swap has happened
            withContext(Dispatchers.IO) { runCatching { a.sendBinary(byteArrayOf(3)) } }
            withContext(Dispatchers.IO) { b.sendBinary(byteArrayOf(4)) }
            rec.awaitCount(3)
            delay(300)
            b.close()
            assertEquals(listOf(bytes(1), bytes(2), bytes(4)), rec.snapshot(), "old connection's frame 3 must be dropped")
        }
    }

    private class RecordingClient(port: Int) : WebSocketClient(URI("ws://127.0.0.1:$port")) {
        val received = ConcurrentLinkedQueue<List<Byte>>()
        override fun onOpen(handshakedata: ServerHandshake?) = Unit
        override fun onMessage(message: String?) = Unit
        override fun onMessage(bytes: ByteBuffer?) {
            bytes?.let { received += ByteArray(it.remaining()).also { a -> it.get(a) }.toList() }
        }
        override fun onClose(code: Int, reason: String?, remote: Boolean) = Unit
        override fun onError(ex: Exception?) = Unit
    }

    @Test
    fun sendEncryptedFrame_reachesOnlyCurrentAuthenticatedPeer() = run20 {
        withServer { server, port ->
            val rec = record(server)
            val a = RecordingClient(port).also { withContext(Dispatchers.IO) { it.connectBlocking() } }
            val b = RecordingClient(port).also { withContext(Dispatchers.IO) { it.connectBlocking() } }
            withContext(Dispatchers.IO) {
                a.send(preamble)
                a.send(byteArrayOf(1))
            }
            rec.awaitCount(1)
            withContext(Dispatchers.IO) {
                b.send(preamble)
                b.send(byteArrayOf(2))
            }
            rec.awaitCount(2) // swap done
            server.sendEncryptedFrame(byteArrayOf(9, 9))
            withTimeout(5_000) { while (b.received.isEmpty()) delay(10) }
            delay(300)
            assertEquals(listOf(bytes(9, 9)), b.received.toList())
            assertTrue(a.received.isEmpty(), "superseded peer must not receive frames")
            withContext(Dispatchers.IO) { runCatching { a.closeBlocking() }; runCatching { b.closeBlocking() } }
        }
    }
}
