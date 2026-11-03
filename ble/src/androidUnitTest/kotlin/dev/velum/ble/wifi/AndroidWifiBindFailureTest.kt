package dev.velum.ble.wifi

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowConnectivityManager
import org.robolectric.shadows.ShadowNetwork

/** Mimics real Android: Network.bindSocket on a connected socket throws (it calls getpeername). */
@Implements(Network::class)
class ConnectedSocketBindShadow : ShadowNetwork() {
    @Implementation
    public override fun bindSocket(socket: Socket) {
        throw IOException("Socket is connected")
    }
}

/** Records the NetworkRequest handed to requestNetwork so the join request can be asserted. */
@Implements(ConnectivityManager::class)
class RecordingConnectivityShadow : ShadowConnectivityManager() {
    @Implementation
    public override fun requestNetwork(request: NetworkRequest, callback: ConnectivityManager.NetworkCallback) {
        requests += request
        super.requestNetwork(request, callback)
    }

    companion object {
        val requests = CopyOnWriteArrayList<NetworkRequest>()
    }
}

/**
 * #96: binding an accepted (already connected) socket to a Network is expected to throw on real
 * Android. The server must report that, not swallow it, and must keep serving the device. The
 * stock Robolectric shadow only records the call, so this test makes it throw like the platform.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], shadows = [ConnectedSocketBindShadow::class, RecordingConnectivityShadow::class])
class AndroidWifiBindFailureTest {
    private val preamble = WifiTransferPreamble.defaultToken("bind-failure")

    private fun runTest(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) {
        runBlocking { withTimeout(60_000) { block() } }
    }

    @Test
    fun bindFailureIsReported_andTheDeviceIsStillServed() = runTest {
        val port = ServerSocket(0).use { it.localPort }
        val failures = CopyOnWriteArrayList<Throwable>()
        val server = AndroidWifiTransferServer(softApNetwork = { ShadowNetwork.newInstance(7) }, onBindFailure = { failures += it })
        server.start(port, "127.0.0.1", preamble)
        try {
            @Suppress("UNCHECKED_CAST")
            val frames = server.incomingEncryptedFrames() as SharedFlow<ByteArray>
            val subscribed = CompletableDeferred<Unit>()
            val first = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(30_000) { frames.onSubscription { subscribed.complete(Unit) }.first() }
            }
            subscribed.await()
            val client = object : WebSocketClient(URI("ws://127.0.0.1:$port")) {
                override fun onOpen(handshakedata: ServerHandshake?) = Unit
                override fun onMessage(message: String?) = Unit
                override fun onClose(code: Int, reason: String?, remote: Boolean) = Unit
                override fun onError(ex: Exception?) = Unit
            }
            check(withContext(Dispatchers.IO) { client.connectBlocking(15, TimeUnit.SECONDS) }) { "device could not connect" }
            withContext(Dispatchers.IO) { client.send(preamble); client.send(byteArrayOf(7)) }
            assertContentEquals(byteArrayOf(7), first.await())
            withTimeout(15_000) { while (failures.isEmpty()) delay(10) }
            val failure = failures.first()
            assertIs<IOException>(failure)
            assertEquals("Socket is connected", failure.message)
            client.closeBlocking()
        } finally {
            server.stop()
        }
    }

    @Test
    fun joinRequestCarriesAWifiNetworkSpecifier() = runTest {
        RecordingConnectivityShadow.requests.clear()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val joiner = AndroidWifiSoftApJoiner(context)
        val join = async(Dispatchers.Default) { joiner.join("PLAUD-FAKE", "password12") }
        withTimeout(15_000) { while (RecordingConnectivityShadow.requests.isEmpty()) delay(10) }
        val request = RecordingConnectivityShadow.requests.single()
        assertTrue(request.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI))
        assertTrue(!request.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR))
        assertIs<WifiNetworkSpecifier>(request.networkSpecifier, "join must use WifiNetworkSpecifier")
        join.cancel()
    }
}
