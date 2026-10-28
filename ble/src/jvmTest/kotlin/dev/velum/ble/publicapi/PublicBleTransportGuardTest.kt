package dev.velum.ble.publicapi

import dev.velum.ble.BleTransport
import dev.velum.ble.GattProfiles
import dev.velum.ble.PlaudBleTransport
import dev.velum.ble.PlaudEncryptedSession
import dev.velum.ble.crypto.createSessionCipher
import dev.velum.ble.fake.FakeBleTransport
import dev.velum.protocol.Bytes
import dev.velum.protocol.ForbiddenOpcodes
import dev.velum.protocol.ForbiddenSettings
import dev.velum.protocol.Framing
import dev.velum.protocol.Protocol20SessionSecret
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Compiles against the **public** `:ble` surface from a sibling package.
 *
 * Sealed ciphertext must never cross a public API. Encrypted writes use
 * [PlaudEncryptedSession.writeEncrypted] with plaintext only; sealing stays
 * inside `:ble`.
 */
class PublicBleTransportGuardTest {
    @Test
    fun publicApi_hasNoMethodThatAcceptsPreSealedBytes() {
        val types = listOf(
            BleTransport::class.java,
            PlaudBleTransport::class.java,
            PlaudEncryptedSession::class.java,
        )
        types.forEach { type ->
            type.methods
                .filter { Modifier.isPublic(it.modifiers) }
                .forEach { method ->
                    assertFalse(
                        method.name.contains("Sealed", ignoreCase = true),
                        "${type.simpleName}.${method.name} must not be a public sealed-byte API",
                    )
                    assertFalse(
                        method.name.equals("writeSealedAfterPlaintextGuard", ignoreCase = true),
                        "writeSealedAfterPlaintextGuard must be removed from the public API",
                    )
                }
        }
        assertFailsWith<ClassNotFoundException> {
            Class.forName("dev.velum.ble.BleWriteSafety")
        }
        assertTrue(
            BleTransport::class.java.methods.none { it.name == "writeSealedAlreadyGuarded" },
            "internal sink must not appear on BleTransport",
        )
        // writeEncrypted takes a single plaintext ByteArray (+ optional boolean), not sealed.
        val writeEncrypted = PlaudEncryptedSession::class.java.methods
            .filter { it.name == "writeEncrypted" && Modifier.isPublic(it.modifiers) }
        assertTrue(writeEncrypted.isNotEmpty())
        writeEncrypted.forEach { method ->
            val byteArrays = method.parameterTypes.count { it == ByteArray::class.java }
            assertEquals(
                1,
                byteArrays,
                "writeEncrypted must take exactly one ByteArray (plaintext): ${method.parameterTypes.toList()}",
            )
        }
    }

    @Test
    fun publicWrite_alwaysRejectsForbiddenPlaintextOn2bb1() = runTest {
        val fake = FakeBleTransport()
        val ble: BleTransport = fake
        ble.connect("fake-note-pro", GattProfiles.Plaud)

        ForbiddenOpcodes.ALL.forEach { op ->
            assertFailsWith<IllegalArgumentException>("public write opcode $op") {
                ble.write(GattProfiles.PLAUD_WRITE, rawAppCommand(op))
            }
        }
        assertTrue(fake.recordedWrites.isEmpty())
    }

    @Test
    fun publicWrite_rejectsForbiddenSettings4And26() = runTest {
        val fake = FakeBleTransport()
        val ble: BleTransport = fake
        ble.connect("fake-note-pro", GattProfiles.Plaud)

        listOf(ForbiddenSettings.AUTO_DELETE, ForbiddenSettings.AUTO_SYNC).forEach { setting ->
            assertFailsWith<IllegalArgumentException>("setting $setting") {
                ble.write(
                    GattProfiles.PLAUD_WRITE,
                    rawAppCommand(8, byteArrayOf(setting.toByte(), 1)),
                )
            }
        }
        assertTrue(fake.recordedWrites.isEmpty())
    }

    @Test
    fun writeEncrypted_rejectsForbiddenPlaintextAndSettings_beforeSealing() = runTest {
        val fake = FakeBleTransport()
        fake.connect("fake-note-pro", GattProfiles.Plaud)
        val plaud = PlaudBleTransport(fake)
        val session = plaud.openEncryptedSession(
            cipher = createSessionCipher(),
            secret = testSecret(),
        )

        ForbiddenOpcodes.ALL.forEach { op ->
            assertFailsWith<IllegalArgumentException>("writeEncrypted opcode $op") {
                session.writeEncrypted(rawAppCommand(op))
            }
        }
        listOf(ForbiddenSettings.AUTO_DELETE, ForbiddenSettings.AUTO_SYNC).forEach { setting ->
            assertFailsWith<IllegalArgumentException>("writeEncrypted setting $setting") {
                session.writeEncrypted(rawAppCommand(8, byteArrayOf(setting.toByte(), 1)))
            }
        }
        assertTrue(fake.recordedWrites.isEmpty())
    }

    @Test
    fun writeEncrypted_bytesOnWireAreExactSessionSealOfPlaintext() = runTest {
        val fake = FakeBleTransport()
        fake.connect("fake-note-pro", GattProfiles.Plaud)
        val cipher = createSessionCipher()
        val secret = testSecret()
        val session = PlaudBleTransport(fake).openEncryptedSession(
            cipher = cipher,
            secret = secret,
            initialSendSeq = 2,
        )

        val plaintext = rawAppCommand(9) // battery — allowed
        val expectedSeq = session.nextSendSequence
        session.writeEncrypted(plaintext)

        assertEquals(1, fake.recordedWrites.size)
        assertEquals(GattProfiles.PLAUD_WRITE, fake.recordedWrites.single().first)
        val onWire = fake.recordedWrites.single().second

        // Decrypt with the same session key and compare to the checked plaintext.
        val opened = cipher.openPacket(secret, expectedSeq, onWire)
        assertTrue(opened != null, "on-wire bytes must open with the session cipher")
        assertEquals(expectedSeq, opened!!.first)
        assertTrue(
            opened.second.contentEquals(plaintext),
            "on-wire seal must decrypt to the guarded plaintext",
        )
        // Independent check: re-seal locally and compare ciphertext bytes exactly.
        val expectedSeal = cipher.sealPacket(secret, expectedSeq, plaintext)
        assertTrue(
            onWire.contentEquals(expectedSeal),
            "on-wire bytes must equal cipher.sealPacket of the checked plaintext",
        )
    }

    private fun testSecret() = Protocol20SessionSecret(
        key = ByteArray(32) { (it + 3).toByte() },
        nonce = ByteArray(12) { (it + 5).toByte() },
        aad = ByteArray(12) { (it + 7).toByte() },
    )

    private fun rawAppCommand(cmd: Int, params: ByteArray = ByteArray(0)): ByteArray {
        val raw = ByteArray(1 + 2 + params.size)
        raw[0] = Framing.APP_PREFIX
        Bytes.u16Le(cmd).copyInto(raw, 1)
        params.copyInto(raw, 3)
        return raw
    }
}
