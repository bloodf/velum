package dev.velum.ble.wifi

import dev.velum.ble.crypto.createSessionCipher
import dev.velum.protocol.Protocol20SessionSecret
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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
        session.start(
            ssid = "PLAUD-FAKE",
            password = "password12",
            port = 18081,
            preamble = preamble,
            bindHost = "127.0.0.1",
        )
        try {
            // Subscribe before send — SharedFlow has no replay; avoid lost-frame race.
            val receive = async {
                withTimeout(5_000) { server.incomingEncryptedFrames().first() }
            }
            delay(20)
            val device = FakeWifiDeviceClient(port = 18081)
            device.connect()
            device.sendPreamble(preamble)
            delay(50)
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
        // Drive device in parallel after server starts inside transferOrFallback.
        val deviceThread = Thread {
            Thread.sleep(200)
            val device = FakeWifiDeviceClient(port = 18082)
            try {
                device.connect()
                device.sendPreamble(preamble)
                Thread.sleep(50)
                device.sendBinary(frameCipher.seal(0, expected))
            } finally {
                device.close()
            }
        }
        deviceThread.start()
        val outcome = coordinator.transferOrFallback(
            ssid = "PLAUD-FAKE",
            password = "password12",
            port = 18082,
            preamble = preamble,
            bindHost = "127.0.0.1",
            assemble = { s ->
                withTimeout(5_000) {
                    val sealed = s.server.incomingEncryptedFrames().first()
                    s.openIncoming(sealed) ?: error("open failed")
                }
            },
        )
        deviceThread.join(10_000)
        assertIs<WifiTransferCoordinator.Outcome.Wifi>(outcome)
        assertEquals(expected.toList(), outcome.bytes.toList())
    }
}
