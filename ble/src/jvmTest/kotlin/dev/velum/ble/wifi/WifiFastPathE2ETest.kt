package dev.velum.ble.wifi

import dev.velum.ble.crypto.createSessionCipher
import dev.velum.protocol.Protocol20SessionSecret
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class WifiFastPathE2ETest {
    private val secret = Protocol20SessionSecret(
        key = ByteArray(32) { (it + 3).toByte() },
        nonce = ByteArray(12) { (it + 7).toByte() },
        aad = ByteArray(12) { (it + 11).toByte() },
    )

    @Test
    fun websocket_preamble_and_encrypted_frame_roundTrip() = runBlocking {
        val server = JavaWebsocketTransferServer()
        val softAp = FakeWifiSoftApJoiner()
        val cipher = createWifiFrameCipher(
            createSessionCipher(),
            secret,
            WifiPayloadCipher.ChaCha20Poly1305,
        )
        val session = WifiFastTransferSession(softAp, server, cipher)
        val preamble = WifiTransferPreamble.defaultToken("e2e")
        val port = freePort()
        session.start(
            ssid = "PLAUD-FAKE",
            password = "password12",
            port = port,
            preamble = preamble,
            bindHost = "127.0.0.1",
        )
        try {
            // Subscribe before send — SharedFlow has no replay; avoid lost-frame race.
            val subscribed = CompletableDeferred<Unit>()
            val receive = async {
                withTimeout(5_000) {
                    (server.incomingEncryptedFrames() as SharedFlow<ByteArray>)
                        .onSubscription { subscribed.complete(Unit) }
                        .first()
                }
            }
            withTimeout(5_000) { subscribed.await() }
            val device = connectWhenReady(port)
            device.sendPreamble(preamble)
            val plaintext = byteArrayOf(0x01, 0x1A, 0x00, 0x01, 0x02, 0x03)
            val sealed = cipher.seal(0, plaintext)
            device.sendBinary(sealed)
            val received = receive.await()
            val opened = session.openIncoming(received)
            assertTrue(opened.contentEquals(plaintext))
            device.close()
        } finally {
            session.stop()
        }
    }

    @Test
    fun coordinator_falls_back_to_ble_when_wifi_unavailable() = runBlocking {
        val blePayload = byteArrayOf(9, 9, 9)
        val coordinator = WifiTransferCoordinator(
            sessionFactory = {
                WifiFastTransferSession(
                    softAp = UnsupportedWifiSoftApJoiner,
                    server = JavaWebsocketTransferServer(),
                    frameCipher = createWifiFrameCipher(
                        createSessionCipher(),
                        secret,
                        WifiPayloadCipher.ChaCha20Poly1305,
                    ),
                )
            },
            bleDownload = { blePayload },
        )
        val outcome = coordinator.transferOrFallback(
            ssid = "x",
            password = "y",
            assemble = { error("should not assemble") },
        )
        assertIs<WifiTransferCoordinator.Outcome.BleFallback>(outcome)
        assertTrue(outcome.bytes.contentEquals(blePayload))
        assertTrue(outcome.reason.isNotBlank())
    }

    @Test
    fun coordinator_wifi_happy_path() = runBlocking {
        val server = JavaWebsocketTransferServer()
        val softAp = FakeWifiSoftApJoiner()
        val frameCipher = createWifiFrameCipher(
            createSessionCipher(),
            secret,
            WifiPayloadCipher.AesGcm,
        )
        val session = WifiFastTransferSession(softAp, server, frameCipher)
        val preamble = WifiTransferPreamble.defaultToken("ok")
        val coordinator = WifiTransferCoordinator(
            sessionFactory = { session },
            bleDownload = { error("BLE should not run") },
        )
        val expected = byteArrayOf(1, 2, 3, 4)
        val port = freePort()
        // Drive device in parallel after server starts inside transferOrFallback.
        val subscribed = CountDownLatch(1)
        val deviceError = AtomicReference<Throwable?>(null)
        val deviceThread = Thread {
            try {
                val device = connectWhenReady(port)
                try {
                    device.sendPreamble(preamble)
                    // SharedFlow has no replay: send only once assemble() is collecting.
                    check(subscribed.await(10, TimeUnit.SECONDS)) { "collector never subscribed" }
                    device.sendBinary(frameCipher.seal(0, expected))
                } finally {
                    device.close()
                }
            } catch (t: Throwable) {
                deviceError.set(t)
            }
        }
        deviceThread.start()
        val outcome = coordinator.transferOrFallback(
            ssid = "PLAUD-FAKE",
            password = "password12",
            port = port,
            preamble = preamble,
            bindHost = "127.0.0.1",
            assemble = { s ->
                withTimeout(5_000) {
                    val sealed = (s.server.incomingEncryptedFrames() as SharedFlow<ByteArray>)
                        .onSubscription { subscribed.countDown() }
                        .first()
                    s.openIncoming(sealed) ?: error("open failed")
                }
            },
        )
        deviceThread.join(10_000)
        assertTrue(!deviceThread.isAlive, "device thread did not finish")
        deviceError.get()?.let { throw it }
        assertIs<WifiTransferCoordinator.Outcome.Wifi>(outcome)
        assertEquals(expected.toList(), outcome.bytes.toList())
    }

    // Ephemeral port: avoids collisions between concurrent test runs.
    private fun freePort(): Int = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }

    // Retry until the server accepts (bounded) instead of a fixed sleep.
    private fun connectWhenReady(port: Int, timeoutMs: Long = 10_000): FakeWifiDeviceClient {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (true) {
            val device = FakeWifiDeviceClient(port = port)
            try {
                device.connect(timeoutMs = 1_000)
                return device
            } catch (e: Exception) {
                device.close()
                if (System.nanoTime() > deadline) throw e
                Thread.sleep(50)
            }
        }
    }
}
