package dev.velum.ble.wifi

import android.content.Context
import android.net.ConnectivityManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.velum.ble.client.PlaudDeviceClient
import dev.velum.ble.crypto.createSessionCipher
import dev.velum.ble.crypto.rsaKeyPairToPem
import dev.velum.ble.fake.FakeBleTransport
import dev.velum.ble.fake.FakePlaudPeripheral
import dev.velum.protocol.DeviceIdentityKeys
import dev.velum.protocol.Protocol20SessionSecret
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowNetwork

/** Android server plus an actual client download over simulated BLE. No physical network proof. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AndroidWifiBleFallbackTest {
    @Test
    fun rejectedFrameAfterPartialWifi_downloadsWholeFileOverSimulatedBle() = runBlocking {
        withTimeout(90_000) {
            for (mode in WifiPayloadCipher.entries) {
                val fixture = ByteArray(64 * 1024) { ((it * 17 + 3) and 0xff).toByte() }
                val keys = rsaKeyPairToPem().let { (pub, priv) ->
                    DeviceIdentityKeys(
                        serialNumber = "881FAKE1",
                        serialSignature = ByteArray(256) { (it % 251).toByte() },
                        publicKeyPem = pub,
                        privateKeyPem = priv,
                        bindToken = "synthetic-token",
                    )
                }
                val peripheral = FakePlaudPeripheral(
                    createSessionCipher(), keys, fixture, requirePublicKey = false,
                    stopCrc = FakePlaudPeripheral.StopCrc.Ccitt,
                )
                val ble = FakeBleTransport(peripheral)
                var bleCalls = 0
                val network = ShadowNetwork.newInstance(96)
                val shadow = Shadow.extract<ShadowNetwork>(network)
                val cm = ApplicationProvider.getApplicationContext<Context>()
                    .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                assertNull(cm.boundNetworkForProcess)
                var joined = false
                var leaves = 0
                val joiner = object : WifiSoftApJoiner {
                    override suspend fun join(ssid: String, password: String, ssidIsPattern: Boolean) {
                        joined = true
                    }
                    override suspend fun leave() { joined = false; leaves++ }
                }
                val secret = Protocol20SessionSecret(
                    ByteArray(32) { (it + 5).toByte() },
                    ByteArray(12) { (it + 9).toByte() },
                    ByteArray(12) { (it + 13).toByte() },
                )
                fun cipher() = createWifiFrameCipher(createSessionCipher(), secret, mode)
                val server = AndroidWifiTransferServer(softApNetwork = { network })
                val session = WifiFastTransferSession(joiner, server, cipher())
                val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
                val preamble = WifiTransferPreamble.defaultToken("android-ble-96")
                val partial = ByteArrayOutputStream()
                val subscribed = CompletableDeferred<Unit>()
                val finishDevice = CompletableDeferred<Unit>()
                val deviceClosed = CompletableDeferred<Unit>()
                val device = object : WebSocketClient(URI("ws://127.0.0.1:$port")) {
                    override fun onOpen(handshakedata: ServerHandshake?) = Unit
                    override fun onMessage(message: String?) = Unit
                    override fun onClose(code: Int, reason: String?, remote: Boolean) { deviceClosed.complete(Unit) }
                    override fun onError(ex: Exception?) = Unit
                }
                try {
                    val outcome = WifiTransferCoordinator(
                        sessionFactory = { session },
                        bleDownload = {
                            bleCalls++
                            // This must follow two decrypted Wi-Fi chunks, rather than an early timeout.
                            assertContentEquals(fixture.copyOf(8192), partial.toByteArray())
                            ble.connect("fake-note-pro")
                            val client = PlaudDeviceClient(ble, createSessionCipher(), keys)
                            try {
                                client.unlock()
                                client.handshake()
                                val entry = client.listSessions().entries.single()
                                client.downloadSession(entry.sessionId, entry.sizeBytes)
                            } finally {
                                client.close()
                            }
                        },
                    ).transferOrFallback(
                        "PLAUD-FAKE", "password12", port = port,
                        preamble = preamble, bindHost = "127.0.0.1",
                        assemble = { s ->
                            // assemble runs only after the real listener is ready. Keep the peer open
                            // until transfer ends; failure injection does not exercise library close races.
                            launch(Dispatchers.IO) {
                                try {
                                    check(device.connectBlocking(15, TimeUnit.SECONDS)) { "mock connection failed" }
                                    subscribed.await()
                                    device.send(preamble)
                                    val sealing = cipher()
                                    repeat(3) { i ->
                                        val sealed = sealing.seal(i.toLong(), fixture.copyOfRange(i * 4096, (i + 1) * 4096))
                                        if (i == 2) sealed[sealed.lastIndex] = (sealed.last().toInt() xor 1).toByte()
                                        device.send(sealed)
                                    }
                                    finishDevice.await()
                                } finally {
                                    device.closeBlocking()
                                }
                            }
                            @Suppress("UNCHECKED_CAST")
                            (s.incomingEncryptedFrames() as SharedFlow<ByteArray>)
                                .onSubscription { subscribed.complete(Unit) }
                                .transformWhile<ByteArray, Unit> { sealed ->
                                    partial.write(s.openIncoming(sealed) ?: throw WifiTransferFailure("wifi_frame_rejected"))
                                    partial.size() < fixture.size
                                }.collect { }
                            partial.toByteArray()
                        },
                    )
                    assertIs<WifiTransferCoordinator.Outcome.BleFallback>(outcome)
                    assertEquals("wifi_frame_rejected", outcome.reason)
                    assertEquals(1, bleCalls)
                    assertEquals(listOf(0L), peripheral.syncStartOffsets)
                    assertContentEquals(fixture, outcome.bytes)
                    fun crc(bytes: ByteArray) = CRC32().also { it.update(bytes) }.value
                    assertEquals(crc(fixture), crc(outcome.bytes))
                    assertEquals(1, shadow.boundSocketCount(), "selected network receives the accepted socket")
                    assertTrue(!joined && leaves > 0)
                    assertNull(cm.boundNetworkForProcess)
                    withTimeout(15_000) { deviceClosed.await() }
                    withContext(Dispatchers.IO) {
                        ServerSocket(port, 1, InetAddress.getByName("127.0.0.1")).close()
                    }
                } finally {
                    finishDevice.complete(Unit)
                    withContext(NonCancellable + Dispatchers.IO) {
                        device.closeBlocking()
                        server.stop()
                        ble.disconnect()
                    }
                }
            }
        }
    }
}
