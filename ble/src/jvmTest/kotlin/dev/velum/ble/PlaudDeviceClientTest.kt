package dev.velum.ble

import dev.velum.ble.client.PlaudDeviceClient
import dev.velum.ble.crypto.JcaFileDecryptor
import dev.velum.ble.crypto.JcaSessionCipher
import dev.velum.ble.crypto.createFileDecryptor
import dev.velum.ble.crypto.createSessionCipher
import dev.velum.ble.crypto.rsaKeyPairToPem
import dev.velum.ble.fake.FakeBleTransport
import dev.velum.ble.fake.FakePlaudPeripheral
import dev.velum.ble.wifi.UnsupportedWifiSoftApJoiner
import dev.velum.ble.wifi.WifiPayloadCipher
import dev.velum.ble.wifi.createWifiFrameCipher
import dev.velum.protocol.DeviceIdentityKeys
import dev.velum.protocol.DeviceModel
import dev.velum.protocol.FeatureFlags
import dev.velum.protocol.ForbiddenOpcodes
import dev.velum.protocol.ForbiddenSettings
import dev.velum.protocol.PlaudAdvertisement
import dev.velum.protocol.PlaudFileCrypto
import dev.velum.protocol.Protocol20SessionSecret
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class PlaudDeviceClientTest {
    @Test
    fun unlock_handshake_list_download_battery() = runTest {
        val (publicPem, privatePem) = rsaKeyPairToPem()
        val keys = DeviceIdentityKeys(
            serialNumber = "881FAKE1",
            serialSignature = ByteArray(256) { (it % 251).toByte() },
            publicKeyPem = publicPem,
            privateKeyPem = privatePem,
            bindToken = "user-token",
        )
        val cipher = createSessionCipher()
        assertTrue(cipher is JcaSessionCipher)

        val plaintextBody = "hello-ogg-opus-fixture".encodeToByteArray()
        val fileDecryptor = createFileDecryptor() as JcaFileDecryptor
        val encryptedFile = fileDecryptor.encryptFile(
            publicKeyPem = publicPem,
            plaintextBody = plaintextBody,
        )

        val peripheral = FakePlaudPeripheral(
            cipher = cipher,
            clientKeys = keys,
            sessionFile = encryptedFile,
            requirePublicKey = true,
        )
        val ble = FakeBleTransport(peripheral)
        ble.startScan()
        assertEquals(1, ble.discoveredDevices.value.size)
        assertEquals(DeviceModel.NotePro.code, ble.discoveredDevices.value[0].modelCode)
        assertEquals(20, ble.discoveredDevices.value[0].protocolVersion)

        val mfg = FakeBleTransport.noteProManufacturerData()
        val adv = PlaudAdvertisement.parseManufacturerData(mfg)!!
        assertEquals(DeviceModel.NotePro, adv.model)
        assertEquals(20, adv.protocolVersion)

        ble.connect(ble.discoveredDevices.value[0].id)

        val client = PlaudDeviceClient(ble, cipher, keys)
        client.unlock()
        val hs = client.handshake()
        assertEquals(20, hs.protocolVersion)

        val sessions = client.listSessions(nowUnix = 1_700_000_111L)
        assertEquals(1, sessions.total)
        assertEquals(encryptedFile.size.toLong(), sessions.entries[0].sizeBytes)

        val downloaded = client.downloadSession(
            sessionId = sessions.entries[0].sessionId,
            sizeBytes = sessions.entries[0].sizeBytes,
        )
        assertEquals(encryptedFile.size, downloaded.size)
        assertTrue(downloaded.contentEquals(encryptedFile))

        val decrypted = PlaudFileCrypto.decrypt(downloaded, privatePem, fileDecryptor)
        assertTrue(decrypted.contentEquals(plaintextBody))

        val battery = client.readBattery()
        assertNotNull(battery)
        assertEquals(0x5d, battery.percent)

        client.close()
    }

    @Test
    fun unlock_withoutPublicKey_whenE2eeOff() = runTest {
        val (publicPem, privatePem) = rsaKeyPairToPem()
        val keys = testKeys(publicPem, privatePem)
        val cipher = createSessionCipher()
        val file = (createFileDecryptor() as JcaFileDecryptor).encryptFile(
            publicPem,
            byteArrayOf(9, 8, 7),
        )
        val peripheral = FakePlaudPeripheral(cipher, keys, file, requirePublicKey = false)
        val ble = FakeBleTransport(peripheral)
        ble.connect("fake-note-pro")
        val client = PlaudDeviceClient(ble, cipher, keys)
        client.unlock()
        assertEquals(20, client.handshake().protocolVersion)
        client.close()
    }

    @Test
    fun reconnect_requiresFe10Again() = runTest {
        val (publicPem, privatePem) = rsaKeyPairToPem()
        val keys = testKeys(publicPem, privatePem)
        val cipher = createSessionCipher()
        val file = (createFileDecryptor() as JcaFileDecryptor).encryptFile(
            publicPem,
            byteArrayOf(1),
        )
        val peripheral = FakePlaudPeripheral(cipher, keys, file, requirePublicKey = false)
        val ble = FakeBleTransport(peripheral)
        ble.connect("fake-note-pro")
        val client = PlaudDeviceClient(ble, cipher, keys)
        client.unlock()
        client.handshake()
        client.close()

        ble.disconnect()
        ble.connect("fake-note-pro")
        val client2 = PlaudDeviceClient(ble, cipher, keys)
        client2.unlock()
        assertEquals(20, client2.handshake().protocolVersion)
        client2.close()
    }

    @Test
    fun forbiddenOpcodes_throw() = runTest {
        val (publicPem, privatePem) = rsaKeyPairToPem()
        val keys = testKeys(publicPem, privatePem, signature = ByteArray(256) { 1 })
        val cipher = createSessionCipher()
        val file = (createFileDecryptor() as JcaFileDecryptor).encryptFile(
            publicPem,
            byteArrayOf(1, 2, 3),
        )
        val peripheral = FakePlaudPeripheral(cipher, keys, file, requirePublicKey = false)
        val ble = FakeBleTransport(peripheral)
        ble.connect("fake-note-pro")
        val client = PlaudDeviceClient(ble, cipher, keys)
        client.unlock()
        client.handshake()

        ForbiddenOpcodes.ALL.forEach { op ->
            assertFailsWith<IllegalArgumentException>("opcode $op") {
                client.writeCommandForTest(op)
            }
        }
        assertTrue(128 in ForbiddenOpcodes.ALL)
        client.close()
    }

    @Test
    fun forbiddenSettings_writeViaCmd8_throws() = runTest {
        val (publicPem, privatePem) = rsaKeyPairToPem()
        val keys = testKeys(publicPem, privatePem, signature = ByteArray(256) { 1 })
        val cipher = createSessionCipher()
        val file = (createFileDecryptor() as JcaFileDecryptor).encryptFile(
            publicPem,
            byteArrayOf(1, 2, 3),
        )
        val peripheral = FakePlaudPeripheral(cipher, keys, file, requirePublicKey = false)
        val ble = FakeBleTransport(peripheral)
        ble.connect("fake-note-pro")
        val client = PlaudDeviceClient(ble, cipher, keys)
        client.unlock()
        client.handshake()

        assertFailsWith<IllegalArgumentException> {
            client.writeCommandForTest(8, byteArrayOf(4, 1))
        }
        assertFailsWith<IllegalArgumentException> {
            client.writeCommandForTest(8, byteArrayOf(ForbiddenSettings.AUTO_SYNC.toByte(), 1))
        }
        client.close()
    }

    @Test
    fun downloadSession_rejectsOversizedCap() = runTest {
        val (publicPem, privatePem) = rsaKeyPairToPem()
        val keys = testKeys(publicPem, privatePem)
        val cipher = createSessionCipher()
        val file = (createFileDecryptor() as JcaFileDecryptor).encryptFile(
            publicPem,
            byteArrayOf(1),
        )
        val peripheral = FakePlaudPeripheral(cipher, keys, file, requirePublicKey = false)
        val ble = FakeBleTransport(peripheral)
        ble.connect("fake-note-pro")
        val client = PlaudDeviceClient(ble, cipher, keys)
        client.unlock()
        client.handshake()
        assertFailsWith<IllegalArgumentException> {
            client.downloadSession(
                sessionId = 1,
                sizeBytes = PlaudDeviceClient.MAX_SESSION_DOWNLOAD_BYTES + 1,
            )
        }
        client.close()
    }

    @Test
    fun sessionCipher_usesFixedNonceAcrossSeq() {
        val cipher = createSessionCipher()
        val secret = Protocol20SessionSecret(
            key = ByteArray(32) { 7 },
            nonce = ByteArray(12) { 3 },
            aad = ByteArray(12) { 9 },
        )
        val packet = byteArrayOf(0x01, 0x09, 0x00)
        val a = cipher.sealPacket(secret, 2, packet)
        val b = cipher.sealPacket(secret, 3, packet)
        // Different plaintext (seq prefix) → different ciphertext; both open with fixed nonce.
        assertFalse(a.contentEquals(b))
        val openedA = cipher.openPacket(secret, 2, a)!!
        val openedB = cipher.openPacket(secret, 3, b)!!
        assertTrue(openedA.second.contentEquals(packet))
        assertTrue(openedB.second.contentEquals(packet))
    }

    @Test
    fun wifiFrameCipher_chacha_and_aes_roundTrip_jvmSkipsSoftAp() {
        assertFailsWith<IllegalStateException> {
            kotlinx.coroutines.runBlocking {
                UnsupportedWifiSoftApJoiner.join("PLAUD-TEST", "password")
            }
        }
        val cipher = createSessionCipher()
        val secret = Protocol20SessionSecret(
            key = ByteArray(32) { (it + 1).toByte() },
            nonce = ByteArray(12) { (it + 2).toByte() },
            aad = ByteArray(12) { (it + 3).toByte() },
        )
        val packet = byteArrayOf(0x01, 0x1A, 0x00)
        val chacha = createWifiFrameCipher(cipher, secret, WifiPayloadCipher.ChaCha20Poly1305)
        val sealed = chacha.seal(0, packet)
        assertTrue(chacha.open(0, sealed)!!.second.contentEquals(packet))

        val flags = FeatureFlags(wifiAesGcm = true, rawBitmap = 0x08)
        assertEquals(WifiPayloadCipher.AesGcm, WifiPayloadCipher.fromFeatureFlags(flags))
        val aes = createWifiFrameCipher(cipher, secret, WifiPayloadCipher.AesGcm)
        val sealedAes = aes.seal(1, packet)
        assertTrue(aes.open(1, sealedAes)!!.second.contentEquals(packet))
    }

    private fun testKeys(
        publicPem: String,
        privatePem: String,
        signature: ByteArray = ByteArray(256) { (it % 251).toByte() },
    ) = DeviceIdentityKeys(
        serialNumber = "881FAKE1",
        serialSignature = signature,
        publicKeyPem = publicPem,
        privateKeyPem = privatePem,
        bindToken = "tok",
    )
}
