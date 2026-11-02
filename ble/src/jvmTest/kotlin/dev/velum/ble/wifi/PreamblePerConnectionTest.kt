package dev.velum.ble.wifi

import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/** The preamble gate is per connection: one peer's state must never authorize or revoke another's. */
class PreamblePerConnectionTest {
    private val preamble = WifiTransferPreamble.defaultToken("per-conn")

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

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

    /** Collects every frame that arrives until [windowMs] after the collector is attached and [send] ran. */
    private suspend fun SharedFlow<ByteArray>.collectWhile(
        windowMs: Long,
        send: suspend () -> Unit,
    ): List<ByteArray> = coroutineScope {
        val subscribed = CompletableDeferred<Unit>()
        val got = async(start = CoroutineStart.UNDISPATCHED) {
            val list = mutableListOf<ByteArray>()
            withTimeoutOrNull(windowMs) {
                onSubscription { subscribed.complete(Unit) }.collect { list += it }
            }
            list
        }
        subscribed.await()
        send()
        got.await()
    }

    @Test
    fun frameWithoutOwnPreamble_isDropped_evenWhenAnotherConnectionAuthenticated() = runBlocking {
        withTimeout(20_000) {
            withServer { server, port ->
                @Suppress("UNCHECKED_CAST")
                val frames = server.incomingEncryptedFrames() as SharedFlow<ByteArray>
                val a = connect(port)
                val b = connect(port)
                val received = frames.collectWhile(windowMs = 1_500) {
                    withContext(Dispatchers.IO) {
                        a.sendPreamble(preamble)
                        a.sendBinary(byteArrayOf(1))
                        runCatching { b.sendBinary(byteArrayOf(66)) } // no preamble on B
                        b.close()
                        a.sendBinary(byteArrayOf(2))
                    }
                }
                a.close()
                assertEquals(listOf(listOf<Byte>(1), listOf<Byte>(2)), received.map { it.toList() })
            }
        }
    }

    @Test
    fun otherConnectionOpeningAndClosing_doesNotRevokeAuthenticatedPeer() = runBlocking {
        withTimeout(20_000) {
            withServer { server, port ->
                @Suppress("UNCHECKED_CAST")
                val frames = server.incomingEncryptedFrames() as SharedFlow<ByteArray>
                val a = connect(port)
                withContext(Dispatchers.IO) { a.sendPreamble(preamble) }
                val b = connect(port)
                withContext(Dispatchers.IO) { b.close() }
                val received = frames.collectWhile(windowMs = 1_500) {
                    withContext(Dispatchers.IO) { a.sendBinary(byteArrayOf(7)) }
                }
                a.close()
                assertEquals(1, received.size, "authenticated peer's frame must survive another connection closing")
                assertContentEquals(byteArrayOf(7), received.single())
            }
        }
    }
}
