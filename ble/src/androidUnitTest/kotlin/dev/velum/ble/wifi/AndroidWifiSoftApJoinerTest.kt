package dev.velum.ble.wifi

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkRequest
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.net.InetAddress
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowConnectivityManager
import org.robolectric.shadows.ShadowNetwork

@Implements(ConnectivityManager::class)
class DeniedNetworkRequestShadow : ShadowConnectivityManager() {
    @Implementation
    public override fun requestNetwork(request: NetworkRequest, callback: ConnectivityManager.NetworkCallback) {
        super.requestNetwork(request, callback)
        throw SecurityException("Network request denied by platform")
    }
}

/**
 * #96: [AndroidWifiSoftApJoiner] against Robolectric's ConnectivityManager shadow. Asserts the
 * transfer-scoped network lifecycle (request, socket-level network exposure, release on success,
 * cancel and failure) and that the process is never bound to the SoftAP network. The real SoftAP
 * join and normal-network restoration stay in #8.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AndroidWifiSoftApJoinerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val shadowCm = shadowOf(cm)
    private val softAp: Network = ShadowNetwork.newInstance(42)

    @Test
    @Config(sdk = [26, 28])
    fun unsupportedApi_rejectsBeforeRequestingNetwork() = runTest {
        val joiner = AndroidWifiSoftApJoiner(context)
        val failure = assertFailsWith<IllegalStateException> { joiner.join("PLAUD-FAKE", "password12") }
        assertEquals("SoftAP join requires Android 10+", failure.message)
        assertTrue(shadowCm.networkCallbacks.isEmpty())
        assertNull(joiner.boundNetwork())
        assertNull(joiner.softApHostAddress())
    }

    @Test
    @Config(sdk = [29, 34], shadows = [DeniedNetworkRequestShadow::class])
    fun platformRequestDenial_failsJoinAndReleasesNetwork() = runTest {
        val joiner = AndroidWifiSoftApJoiner(context)
        val join = async(Dispatchers.Default) { runCatching { joiner.join("PLAUD-FAKE", "password12") } }
        val failure = assertIs<SecurityException>(join.await().exceptionOrNull())
        assertEquals("Network request denied by platform", failure.message)
        assertTrue(shadowCm.networkCallbacks.isEmpty())
        assertNull(joiner.boundNetwork())
        assertNull(joiner.softApHostAddress())
        assertNull(cm.boundNetworkForProcess)
    }

    private fun runTest(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) {
        runBlocking { withTimeout(60_000) { block() } }
    }

    private suspend fun awaitRegistered() {
        while (shadowCm.networkCallbacks.isEmpty()) delay(10)
    }

    // LinkAddress / addLinkAddress are hidden API: the Robolectric runtime has them, the compile stubs do not.
    private fun linkProps(ip: String): LinkProperties {
        val address = LinkAddress::class.java.getConstructor(InetAddress::class.java, Int::class.javaPrimitiveType)
            .newInstance(InetAddress.getByName(ip), 24)
        return LinkProperties().also { props ->
            LinkProperties::class.java.getMethod("addLinkAddress", LinkAddress::class.java).invoke(props, address)
        }
    }

    @Test
    @Config(sdk = [29, 34])
    fun join_exposesNetworkAndHost_neverBindsProcess_andLeaveReleasesEverything() = runTest {
        val joiner = AndroidWifiSoftApJoiner(context)
        val join = async(Dispatchers.Default) { joiner.join("PLAUD-FAKE", "password12") }
        awaitRegistered()
        assertEquals(1, shadowCm.networkCallbacks.size, "one transfer-scoped network request")
        assertNull(joiner.boundNetwork(), "no network before the SoftAP is available")

        val cb = shadowCm.networkCallbacks.single()
        cb.onLinkPropertiesChanged(softAp, linkProps("10.9.9.9"))
        assertFalse(join.isCompleted, "properties before availability must be ignored")
        assertNull(joiner.boundNetwork())
        cb.onAvailable(softAp)
        assertFalse(join.isCompleted, "availability alone must not complete join")
        assertNull(joiner.boundNetwork())
        assertNull(joiner.softApHostAddress())
        cb.onLinkPropertiesChanged(ShadowNetwork.newInstance(99), linkProps("10.9.9.9"))
        assertFalse(join.isCompleted, "another network must not complete join")
        cb.onLinkPropertiesChanged(softAp, linkProps("192.168.43.1"))
        join.await()
        assertEquals(softAp, joiner.boundNetwork())
        assertEquals("192.168.43.1", joiner.softApHostAddress())
        assertNull(cm.boundNetworkForProcess, "SoftAP must be socket-bound only, never process-wide")

        joiner.leave()
        assertTrue(shadowCm.networkCallbacks.isEmpty(), "callback unregistered on leave")
        assertNull(joiner.boundNetwork())
        assertNull(joiner.softApHostAddress())
        assertNull(cm.boundNetworkForProcess)
    }

    @Test
    fun linkPropertyChangeUpdatesHost_forTheBoundNetworkOnly() = runTest {
        val joiner = AndroidWifiSoftApJoiner(context)
        val join = async(Dispatchers.Default) { joiner.join("PLAUD-FAKE", "password12") }
        awaitRegistered()
        val cb = shadowCm.networkCallbacks.single()
        cb.onAvailable(softAp)
        cb.onLinkPropertiesChanged(softAp, LinkProperties())
        join.await()
        cb.onLinkPropertiesChanged(ShadowNetwork.newInstance(99), linkProps("10.9.9.9"))
        assertNull(joiner.softApHostAddress(), "another network's properties are ignored")
        cb.onLinkPropertiesChanged(softAp, linkProps("192.168.4.1"))
        assertEquals("192.168.4.1", joiner.softApHostAddress())
        joiner.leave()
    }

    @Test
    fun cancelledJoin_releasesTheNetworkRequest() = runTest {
        val joiner = AndroidWifiSoftApJoiner(context)
        val join = async(Dispatchers.Default, start = CoroutineStart.DEFAULT) { joiner.join("PLAUD-FAKE", "password12") }
        awaitRegistered()
        val cb = shadowCm.networkCallbacks.single()
        cb.onAvailable(softAp)
        join.cancel()
        join.join()
        assertTrue(shadowCm.networkCallbacks.isEmpty())
        cb.onAvailable(softAp)
        cb.onLinkPropertiesChanged(softAp, linkProps("192.168.43.1"))
        cb.onUnavailable()
        assertNull(joiner.softApHostAddress())
        assertNull(joiner.boundNetwork())
        assertNull(cm.boundNetworkForProcess)
    }

    @Test
    fun staleCallback_cannotReplaceNewJoinBinding() = runTest {
        val joiner = AndroidWifiSoftApJoiner(context)
        val first = async(Dispatchers.Default) { joiner.join("PLAUD-FAKE", "password12") }
        awaitRegistered()
        val stale = shadowCm.networkCallbacks.single()
        first.cancel()
        first.join()
        val second = async(Dispatchers.Default) { joiner.join("PLAUD-FAKE", "password12") }
        awaitRegistered()
        val current = shadowCm.networkCallbacks.single()
        current.onAvailable(softAp)
        stale.onAvailable(softAp)
        stale.onLinkPropertiesChanged(softAp, linkProps("10.9.9.9"))
        stale.onUnavailable()
        assertFalse(second.isCompleted)
        assertNull(joiner.boundNetwork())
        current.onLinkPropertiesChanged(softAp, linkProps("192.168.43.1"))
        second.await()
        stale.onLinkPropertiesChanged(softAp, linkProps("10.9.9.9"))
        assertEquals(softAp, joiner.boundNetwork())
        assertEquals("192.168.43.1", joiner.softApHostAddress())
        assertEquals(1, shadowCm.networkCallbacks.size)
        joiner.leave()
        current.onAvailable(softAp)
        current.onLinkPropertiesChanged(softAp, linkProps("10.9.9.9"))
        assertNull(joiner.boundNetwork())
        assertNull(joiner.softApHostAddress())
    }

    @Test
    fun unavailableNetwork_failsJoin_andCoordinatorReleasesItViaBleFallback() = runTest {
        val joiner = AndroidWifiSoftApJoiner(context)
        val ble = byteArrayOf(1, 2, 3)
        val coordinator = WifiTransferCoordinator(
            sessionFactory = {
                WifiFastTransferSession(joiner, NoopServer, createWifiFrameCipher(
                    dev.velum.ble.crypto.createSessionCipher(),
                    dev.velum.protocol.Protocol20SessionSecret(ByteArray(32), ByteArray(12), ByteArray(12)),
                    WifiPayloadCipher.ChaCha20Poly1305,
                ))
            },
            bleDownload = { ble },
            wifiTimeout = 30.seconds,
        )
        val outcome = async(Dispatchers.Default) {
            coordinator.transferOrFallback("PLAUD-FAKE", "password12", assemble = { error("not reached") })
        }
        awaitRegistered()
        shadowCm.networkCallbacks.single().onUnavailable()
        val result = outcome.await()
        assertIs<WifiTransferCoordinator.Outcome.BleFallback>(result)
        assertTrue(result.reason.contains("SoftAP network unavailable"), result.reason)
        assertTrue(!result.reason.contains("PLAUD-FAKE"), "the SSID must not reach the fallback reason")
        assertEquals(ble.toList(), result.bytes.toList())
        assertTrue(shadowCm.networkCallbacks.isEmpty(), "failed join still releases the network request")
        assertNull(joiner.boundNetwork())
        assertNull(cm.boundNetworkForProcess)
    }

    private object NoopServer : WifiTransferServer {
        override suspend fun start(port: Int, bindHost: String?, preamble: ByteArray) = Unit
        override suspend fun stop() = Unit
        override fun incomingEncryptedFrames() = kotlinx.coroutines.flow.emptyFlow<ByteArray>()
        override suspend fun sendEncryptedFrame(bytes: ByteArray) = Unit
    }
}
