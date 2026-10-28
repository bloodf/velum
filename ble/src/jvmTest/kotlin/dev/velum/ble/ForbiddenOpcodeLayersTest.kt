package dev.velum.ble

import dev.velum.ble.client.PlaudDeviceClient
import dev.velum.ble.crypto.JcaFileDecryptor
import dev.velum.ble.crypto.createFileDecryptor
import dev.velum.ble.crypto.createSessionCipher
import dev.velum.ble.crypto.rsaKeyPairToPem
import dev.velum.ble.fake.FakeBleTransport
import dev.velum.ble.fake.FakePlaudPeripheral
import dev.velum.protocol.Bytes
import dev.velum.protocol.DeviceIdentityKeys
import dev.velum.protocol.ForbiddenOpcodes
import dev.velum.protocol.ForbiddenSettings
import dev.velum.protocol.Framing
import dev.velum.protocol.PlaudOutboundGuard
import dev.velum.protocol.PlaudUuids
import dev.velum.protocol.Protocol20SessionSecret
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Forbidden opcodes/settings must be rejected at the Plaud send path **and**
 * at the transport / encrypted-session write layers for the Plaud write
 * characteristic (UUID 2BB1). Nothing may reach the fake peripheral write log.
 *
 * Raw forbidden frames are only ever aimed at [FakeBleTransport] — never at a
 * real radio.
 */
class ForbiddenOpcodeLayersTest {
    @Test
    fun outboundGuard_rejectsEveryForbiddenOpcodeAndSetting() {
        ForbiddenOpcodes.ALL.forEach { op ->
            assertFailsWith<IllegalArgumentException>("opcode $op") {
                PlaudOutboundGuard.requireAllowed(rawAppCommand(op))
            }
            assertFailsWith<IllegalArgumentException>("raw opcode $op") {
                PlaudOutboundGuard.requireAllowed(Bytes.u16Le(op))
            }
        }
        assertFailsWith<IllegalArgumentException> {
            PlaudOutboundGuard.requireAllowed(
                rawAppCommand(8, byteArrayOf(ForbiddenSettings.AUTO_DELETE.toByte(), 1)),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            PlaudOutboundGuard.requireAllowed(
                rawAppCommand(8, byteArrayOf(ForbiddenSettings.AUTO_SYNC.toByte(), 1)),
            )
        }
        PlaudOutboundGuard.requireAllowed(rawAppCommand(9))
    }

    @Test
    fun transportLayer_rejectsPlaintextForbidden_andRecordsNothing() = runTest {
        val ble = FakeBleTransport()
        ble.connect("fake-note-pro", GattProfiles.Plaud)

        ForbiddenOpcodes.ALL.forEach { op ->
            ble.clearRecordedWrites()
            assertFailsWith<IllegalArgumentException>("transport opcode $op") {
                ble.write(GattProfiles.PLAUD_WRITE, rawAppCommand(op))
            }
            assertTrue(
                ble.recordedWrites.isEmpty(),
                "forbidden opcode $op must not reach fake transport writes",
            )
        }

        listOf(ForbiddenSettings.AUTO_DELETE, ForbiddenSettings.AUTO_SYNC).forEach { setting ->
            ble.clearRecordedWrites()
            assertFailsWith<IllegalArgumentException>("transport setting $setting") {
                ble.write(
                    GattProfiles.PLAUD_WRITE,
                    rawAppCommand(8, byteArrayOf(setting.toByte(), 1)),
                )
            }
            assertTrue(
                ble.recordedWrites.isEmpty(),
                "forbidden setting $setting must not reach fake transport writes",
            )
        }
    }

    @Test
    fun noPublicApi_acceptsArbitrarySealedBytes() {
        // Negative: writeSealedAfterPlaintextGuard is gone; no public dual-ByteArray write.
        assertTrue(
            PlaudBleTransport::class.java.methods.none {
                it.name == "writeSealedAfterPlaintextGuard"
            },
        )
        assertTrue(
            PlaudEncryptedSession::class.java.methods
                .filter { it.name.startsWith("write") }
                .none { method ->
                    method.parameterTypes.count { it == ByteArray::class.java } > 1
                },
            "no public write* method may take multiple ByteArray args (plaintext+sealed)",
        )
    }

    @Test
    fun writeEncrypted_rejectsForbiddenPlaintext_beforeAnySeal() = runTest {
        val ble = FakeBleTransport()
        ble.connect("fake-note-pro", GattProfiles.Plaud)
        val session = PlaudBleTransport(ble).openEncryptedSession(
            cipher = createSessionCipher(),
            secret = Protocol20SessionSecret(
                key = ByteArray(32) { 1 },
                nonce = ByteArray(12) { 2 },
                aad = ByteArray(12) { 3 },
            ),
        )

        ForbiddenOpcodes.ALL.forEach { op ->
            assertFailsWith<IllegalArgumentException>("encrypted opcode $op") {
                session.writeEncrypted(rawAppCommand(op))
            }
            assertTrue(
                ble.recordedWrites.isEmpty(),
                "forbidden plaintext $op must not reach ble.write via writeEncrypted",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            session.writeEncrypted(
                rawAppCommand(8, byteArrayOf(ForbiddenSettings.AUTO_SYNC.toByte(), 1)),
            )
        }
        assertTrue(ble.recordedWrites.isEmpty())
    }

    @Test
    fun writeEncrypted_onWireEqualsSealOfCheckedPlaintext() = runTest {
        val ble = FakeBleTransport()
        ble.connect("fake-note-pro", GattProfiles.Plaud)
        val cipher = createSessionCipher()
        val secret = Protocol20SessionSecret(
            key = ByteArray(32) { (it + 11).toByte() },
            nonce = ByteArray(12) { (it + 13).toByte() },
            aad = ByteArray(12) { (it + 17).toByte() },
        )
        val session = PlaudBleTransport(ble).openEncryptedSession(cipher, secret, initialSendSeq = 2)
        val plaintext = rawAppCommand(9)
        val seq = session.nextSendSequence
        session.writeEncrypted(plaintext)

        val onWire = ble.recordedWrites.single().second
        val opened = cipher.openPacket(secret, seq, onWire)!!
        assertTrue(opened.second.contentEquals(plaintext))
        assertTrue(onWire.contentEquals(cipher.sealPacket(secret, seq, plaintext)))
    }

    @Test
    fun guardKeysOnPlaudWriteUuid_notProfileId() = runTest {
        val spoof = GattProfile(
            id = "not-plaud",
            displayName = "Spoof",
            serviceUuid = PlaudUuids.SERVICE,
            characteristics = listOf(
                GattCharacteristic(
                    name = "tx",
                    uuid = PlaudUuids.WRITE,
                    canWrite = true,
                ),
            ),
        )
        val ble = FakeBleTransport()
        ble.connect("dev", spoof)
        assertFailsWith<IllegalArgumentException> {
            ble.write("tx", rawAppCommand(ForbiddenOpcodes.CLEAR_ALL))
        }
        assertTrue(ble.recordedWrites.isEmpty())

        ble.connect("dev", GattProfiles.Limitless)
        ble.write(GattProfiles.LIMITLESS_TX, rawAppCommand(ForbiddenOpcodes.CLEAR_ALL))
        assertEquals(1, ble.recordedWrites.size)
    }

    @Test
    fun plaudSendPath_rejectsForbiddenBeforeSeal_encryptedSession() = runTest {
        val env = unlockedClient()
        env.ble.clearRecordedWrites()

        ForbiddenOpcodes.ALL.forEach { op ->
            assertFailsWith<IllegalArgumentException>("client opcode $op") {
                env.client.writeCommandForTest(op)
            }
            assertTrue(
                env.ble.recordedWrites.isEmpty(),
                "client forbidden opcode $op must not reach fake transport after unlock",
            )
        }

        assertFailsWith<IllegalArgumentException> {
            env.client.writeCommandForTest(8, byteArrayOf(ForbiddenSettings.AUTO_DELETE.toByte(), 1))
        }
        assertFailsWith<IllegalArgumentException> {
            env.client.writeCommandForTest(8, byteArrayOf(ForbiddenSettings.AUTO_SYNC.toByte(), 1))
        }
        assertTrue(env.ble.recordedWrites.isEmpty())

        assertFailsWith<IllegalArgumentException> {
            Framing.encodeCommand(ForbiddenOpcodes.CLEAR_ALL)
        }
        env.client.close()
    }

    @Test
    fun encryptedSession_safeCommandWrite_isNotRejectedAsOpcode() = runTest {
        val env = unlockedClient()
        env.ble.clearRecordedWrites()

        val battery = env.client.readBattery()
        assertEquals(0x5d, battery!!.percent)
        assertTrue(
            env.ble.recordedWrites.isNotEmpty(),
            "encrypted safe command must reach ble.write",
        )
        env.ble.recordedWrites.forEach { (_, payload) ->
            assertTrue(payload.size >= 16, "sealed packet too short: ${payload.size}")
        }
        env.client.close()
    }

    @Test
    fun plaudWrapper_routesThroughNamedCharacteristics() = runTest {
        val ble = FakeBleTransport()
        ble.connect("fake-note-pro", GattProfiles.Plaud)
        val plaud = PlaudBleTransport(ble)
        plaud.write(byteArrayOf(0x01, 0x09, 0x00), withoutResponse = true)
        assertEquals(GattProfiles.PLAUD_WRITE, ble.recordedWrites.single().first)
    }

    private data class UnlockedEnv(
        val ble: FakeBleTransport,
        val client: PlaudDeviceClient,
    )

    private suspend fun unlockedClient(): UnlockedEnv {
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
        ble.connect("fake-note-pro", GattProfiles.Plaud)
        val client = PlaudDeviceClient(ble, cipher, keys)
        client.unlock()
        client.handshake()
        return UnlockedEnv(ble, client)
    }

    companion object {
        fun rawAppCommand(cmd: Int, params: ByteArray = ByteArray(0)): ByteArray {
            val raw = ByteArray(1 + 2 + params.size)
            raw[0] = Framing.APP_PREFIX
            Bytes.u16Le(cmd).copyInto(raw, 1)
            params.copyInto(raw, 3)
            return raw
        }
    }
}
