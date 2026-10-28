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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Forbidden opcodes/settings must be rejected at the Plaud send path **and**
 * at the transport write layer for the Plaud profile. Nothing may reach the
 * fake peripheral / recorded write log.
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
        // Safe command still allowed.
        PlaudOutboundGuard.requireAllowed(rawAppCommand(9))
    }

    @Test
    fun transportLayer_rejectsForbidden_andRecordsNothing() = runTest {
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

        // Non-Plaud profile is not subject to Plaud opcode parsing.
        ble.connect("dev", GattProfiles.Omi)
        // Omi has no write chars — use Limitless for a benign write.
        ble.connect("dev", GattProfiles.Limitless)
        ble.write(GattProfiles.LIMITLESS_TX, rawAppCommand(ForbiddenOpcodes.CLEAR_ALL))
        assertEquals(1, ble.recordedWrites.size)
    }

    @Test
    fun plaudSendPath_rejectsForbidden_andRecordsNothingOnFake() = runTest {
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

        ble.clearRecordedWrites()
        ForbiddenOpcodes.ALL.forEach { op ->
            assertFailsWith<IllegalArgumentException>("client opcode $op") {
                client.writeCommandForTest(op)
            }
            assertTrue(
                ble.recordedWrites.isEmpty(),
                "client forbidden opcode $op must not reach fake transport",
            )
        }

        assertFailsWith<IllegalArgumentException> {
            client.writeCommandForTest(8, byteArrayOf(ForbiddenSettings.AUTO_DELETE.toByte(), 1))
        }
        assertFailsWith<IllegalArgumentException> {
            client.writeCommandForTest(8, byteArrayOf(ForbiddenSettings.AUTO_SYNC.toByte(), 1))
        }
        assertTrue(ble.recordedWrites.isEmpty())

        // Framing.encodeCommand also refuses forbidden opcodes (defense in depth).
        assertFailsWith<IllegalArgumentException> {
            Framing.encodeCommand(ForbiddenOpcodes.CLEAR_ALL)
        }
        client.close()
    }

    @Test
    fun plaudWrapper_routesThroughNamedCharacteristics() = runTest {
        val ble = FakeBleTransport()
        ble.connect("fake-note-pro", GattProfiles.Plaud)
        val plaud = PlaudBleTransport(ble)
        plaud.write(byteArrayOf(0x01, 0x09, 0x00), withoutResponse = true)
        assertEquals(GattProfiles.PLAUD_WRITE, ble.recordedWrites.single().first)
    }

    companion object {
        /** Build a plaintext APP frame without going through [Framing.encodeCommand]. */
        fun rawAppCommand(cmd: Int, params: ByteArray = ByteArray(0)): ByteArray {
            val raw = ByteArray(1 + 2 + params.size)
            raw[0] = Framing.APP_PREFIX
            Bytes.u16Le(cmd).copyInto(raw, 1)
            params.copyInto(raw, 3)
            return raw
        }
    }
}
