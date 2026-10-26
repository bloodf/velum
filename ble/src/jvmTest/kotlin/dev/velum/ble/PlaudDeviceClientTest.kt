package dev.velum.ble

import dev.velum.ble.client.PlaudDeviceClient
import dev.velum.ble.crypto.JcaFileDecryptor
import dev.velum.ble.crypto.JcaSessionCipher
import dev.velum.ble.crypto.createFileDecryptor
import dev.velum.ble.crypto.createSessionCipher
import dev.velum.ble.crypto.rsaKeyPairToPem
import dev.velum.ble.fake.FakeBleTransport
import dev.velum.ble.fake.FakePlaudPeripheral
import dev.velum.protocol.DeviceIdentityKeys
import dev.velum.protocol.DeviceModel
import dev.velum.protocol.ForbiddenOpcodes
import dev.velum.protocol.PlaudAdvertisement
import dev.velum.protocol.PlaudFileCrypto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
        assertEquals(0x5d, battery.percent)

        client.close()
    }

    @Test
    fun forbiddenOpcodes_throw() = runTest {
        val (publicPem, privatePem) = rsaKeyPairToPem()
        val keys = DeviceIdentityKeys(
            serialNumber = "881FAKE1",
            serialSignature = ByteArray(256) { 1 },
            publicKeyPem = publicPem,
            privateKeyPem = privatePem,
            bindToken = "tok",
        )
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
        client.close()
    }
}
