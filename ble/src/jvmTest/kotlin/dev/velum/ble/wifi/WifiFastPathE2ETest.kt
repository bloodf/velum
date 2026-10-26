package dev.velum.ble.wifi

import dev.velum.ble.client.PlaudDeviceClient
import dev.velum.ble.crypto.createSessionCipher
import dev.velum.ble.crypto.rsaKeyPairToPem
import dev.velum.ble.fake.FakeBleTransport
import dev.velum.ble.fake.FakePlaudPeripheral
import dev.velum.protocol.DeviceIdentityKeys
import dev.velum.protocol.FeatureFlags
import dev.velum.protocol.Protocol20SessionSecret
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

class WifiFastPathE2ETest {
    private val secret = Protocol20SessionSecret(
        key = ByteArray(32) { (it + 3).toByte() },
        nonce = ByteArray(12) { (it + 7).toByte() },
        aad = ByteArray(12) { (it + 11).toByte() },
    )

    @Test
    fun framing_rejectsOversizeAndTruncatedLength() {
        assertFalse(WifiTransferFraming.isValidFrameLength(0))
        assertFalse(WifiTransferFraming.isValidFrameLength(-1))
        assertFalse(WifiTransferFraming.isValidFrameLength(WifiTransferFraming.MAX_FRAME_BYTES + 1))
        assertTrue(WifiTransferFraming.isValidFrameLength(1))
        val oversizePrefix = byteArrayOf(0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        assertEquals(null, WifiTransferFraming.parseLength(oversizePrefix))
        assertEquals(null, WifiTransferFraming.parseLength(byteArrayOf(0, 0, 1)))
        assertEquals(4, WifiTransferFraming.parseLength(WifiTransferFraming.encodeLength(4)))
    }

    @Test
    fun preamble_reject_fallsBackToBle() = runBlocking {
        val port = freePort()
        val network = FakeWifiNetwork(port = port)
        val frameCipher = createWifiFrameCipher(
            createSessionCipher(),
            secret,
            WifiPayloadCipher.AesGcm,
        )
        val expected = WifiTransferPreamble.fromBindToken("good-token")
        val blePayload = byteArrayOf(0xBE.toByte(), 0xEF.toByte())
        val coordinator = WifiTransferCoordinator(
            sessionFactory = { network.session(frameCipher) },
            bleDownload = { blePayload },
            wifiTimeout = 3.seconds,
        )
        val flags = FeatureFlags(wifiAesGcm = true, rawBitmap = 0x08)
        val device = async(Dispatchers.IO) {
            delay(150)
            FakeWifiClient().connectSendRawPreamble(port, WifiTransferPreamble.fromBindToken("bad"))
        }
        val outcome = coordinator.transferOrFallback(
            featureFlags = flags,
            ssid = "PLAUD-FAKE",
            password = "password12",
            expectedPreamble = expected,
            port = port,
            bindAddress = "127.0.0.1",
            sizeBytes = blePayload.size.toLong(),
        )
        device.await()
        assertIs<WifiTransferCoordinator.Outcome.BleFallback>(outcome)
        assertTrue(outcome.bytes.contentEquals(blePayload))
    }

    @Test
    fun softAp_tokenHandshake_transfer_decryptAssemble() = runBlocking {
        val port = freePort()
        val network = FakeWifiNetwork(port = port)
        val frameCipher = createWifiFrameCipher(
            createSessionCipher(),
            secret,
            WifiPayloadCipher.AesGcm,
        )
        val sessionFile = ByteArray(250) { (it % 251).toByte() }
        val preamble = WifiTransferPreamble.fromSessionKey(secret.key)
        val flags = FeatureFlags(wifiAesGcm = true, rawBitmap = 0x08)
        val coordinator = WifiTransferCoordinator(
            sessionFactory = { network.session(frameCipher) },
            bleDownload = { byteArrayOf(0xFF.toByte()) },
            wifiTimeout = 10.seconds,
        )
        val chunks = sessionFile.toList().chunked(64).map { it.toByteArray() }
        val device = async(Dispatchers.IO) {
            delay(200)
            FakeWifiClient().connectSendSealedSession(
                port = port,
                preamble = preamble,
                frameCipher = frameCipher,
                plaintextChunks = chunks,
            )
        }
        val outcome = coordinator.transferOrFallback(
            featureFlags = flags,
            ssid = "PLAUD-FAKE",
            password = "password12",
            expectedPreamble = preamble,
            port = port,
            bindAddress = network.bindAddress,
            sizeBytes = sessionFile.size.toLong(),
        )
        device.await()
        check(outcome is WifiTransferCoordinator.Outcome.Wifi) {
            "expected Wifi outcome, got $outcome"
        }
        assertTrue(outcome.bytes.contentEquals(sessionFile))
    }

    @Test
    fun featureBitAbsent_usesBleWithoutWifi() = runBlocking {
        val blePayload = byteArrayOf(1, 2, 3)
        val coordinator = WifiTransferCoordinator(
            sessionFactory = { error("sessionFactory must not run without bit 0x08") },
            bleDownload = { blePayload },
        )
        val outcome = coordinator.transferOrFallback(
            featureFlags = FeatureFlags(wifiAesGcm = false, rawBitmap = 0),
            ssid = "x",
            password = "y",
            expectedPreamble = WifiTransferPreamble.fromBindToken("t"),
        )
        assertIs<WifiTransferCoordinator.Outcome.BleFallback>(outcome)
        assertEquals("feature_bit_0x08_absent", outcome.reason)
        assertTrue(outcome.bytes.contentEquals(blePayload))
    }

    @Test
    fun wifiFailure_fallsBackToBleDownloadSession_againstFakePeripheral() = runBlocking {
        val (publicPem, privatePem) = rsaKeyPairToPem()
        val keys = DeviceIdentityKeys(
            serialNumber = "881FAKE1",
            serialSignature = ByteArray(256) { (it % 251).toByte() },
            publicKeyPem = publicPem,
            privateKeyPem = privatePem,
            bindToken = "wifi-e2e",
        )
        val cipher = createSessionCipher()
        val sessionFile = byteArrayOf(0x10, 0x20, 0x30, 0x40, 0x50)
        val peripheral = FakePlaudPeripheral(
            cipher = cipher,
            clientKeys = keys,
            sessionFile = sessionFile,
            requirePublicKey = false,
        )
        val ble = FakeBleTransport(peripheral)
        ble.connect("fake-note-pro")
        val client = PlaudDeviceClient(ble, cipher, keys)
        client.unlock()
        client.handshake()

        val port = freePort()
        val network = FakeWifiNetwork(port = port)
        val frameCipher = createWifiFrameCipher(
            cipher,
            client.sessionSecret!!,
            WifiPayloadCipher.AesGcm,
        )
        val coordinator = WifiTransferCoordinator(
            sessionFactory = { network.session(frameCipher) },
            bleDownload = {
                client.downloadSession(
                    sessionId = 1_700_000_000L,
                    sizeBytes = sessionFile.size.toLong(),
                )
            },
            wifiTimeout = 1.seconds,
        )
        val outcome = client.downloadSessionPreferWifi(
            sessionId = 1_700_000_000L,
            sizeBytes = sessionFile.size.toLong(),
            featureFlags = FeatureFlags(wifiAesGcm = true, rawBitmap = 0x08),
            wifi = coordinator,
            ssid = "PLAUD-FAKE",
            password = "password12",
            expectedPreamble = WifiTransferPreamble.fromBindToken(keys.bindToken),
            bindAddress = "127.0.0.1",
            port = port,
        )
        assertIs<WifiTransferCoordinator.Outcome.BleFallback>(outcome)
        assertTrue(outcome.bytes.contentEquals(sessionFile))
        client.close()
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }
}
