package dev.velum.ble.publicapi

import dev.velum.ble.AbstractBleTransport
import dev.velum.ble.BleTransport
import dev.velum.ble.GattProfiles
import dev.velum.ble.PlaudBleTransport
import dev.velum.ble.PlaudEncryptedSession
import dev.velum.ble.StubBleTransport
import dev.velum.ble.crypto.createSessionCipher
import dev.velum.ble.fake.FakeBleTransport
import dev.velum.protocol.Bytes
import dev.velum.protocol.ForbiddenOpcodes
import dev.velum.protocol.ForbiddenSettings
import dev.velum.protocol.Framing
import dev.velum.protocol.Protocol20SessionSecret
import dev.velum.protocol.SessionCipher
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
            "internal sealed write must not appear on BleTransport",
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
    fun publicOpenEncryptedSession_acceptsSecretOnly_notSessionCipher() {
        val publicOpens = PlaudBleTransport::class.java.methods.filter {
            it.name == "openEncryptedSession" && Modifier.isPublic(it.modifiers)
        }
        assertTrue(publicOpens.isNotEmpty(), "public openEncryptedSession(secret) must exist")
        publicOpens.forEach { method ->
            assertTrue(
                method.parameterTypes.none { SessionCipher::class.java.isAssignableFrom(it) },
                "public openEncryptedSession must not take SessionCipher: ${method.parameterTypes.toList()}",
            )
            assertTrue(
                method.parameterTypes.any { it == Protocol20SessionSecret::class.java },
                "public openEncryptedSession must take Protocol20SessionSecret",
            )
        }
        // sessionSecret / nextSendSequence are internal — not public JVM methods (unmangled).
        listOf(PlaudEncryptedSession::class.java).forEach { type ->
            assertTrue(
                type.methods.none {
                    it.name == "getSessionSecret" && Modifier.isPublic(it.modifiers)
                },
                "${type.simpleName}.sessionSecret must not be public",
            )
            assertTrue(
                type.methods.none {
                    it.name == "getNextSendSequence" && Modifier.isPublic(it.modifiers)
                },
                "${type.simpleName}.nextSendSequence must not be public",
            )
        }
    }

    @Test
    fun concreteTransports_writeSealedAlreadyGuarded_isInternalNotPublic() {
        // Explicit `internal override` mangles to writeSealedAlreadyGuarded$<module>.
        // The unmangled name must not appear as a public JVM method.
        val concretes = buildList {
            add(FakeBleTransport::class.java)
            add(StubBleTransport::class.java)
            add(AbstractBleTransport::class.java)
            runCatching { Class.forName("dev.velum.ble.KableBleTransport") }
                .getOrNull()
                ?.let { add(it) }
        }
        assertTrue(concretes.size >= 3, "expected Fake + Stub + Abstract at minimum")
        concretes.forEach { type ->
            val unmangledPublic = (type.declaredMethods + type.methods).filter {
                it.name == "writeSealedAlreadyGuarded" && Modifier.isPublic(it.modifiers)
            }
            assertTrue(
                unmangledPublic.isEmpty(),
                "${type.simpleName}: unmangled writeSealedAlreadyGuarded must not be public",
            )
            val mangled = (type.declaredMethods + type.methods).filter {
                it.name.startsWith("writeSealedAlreadyGuarded\$")
            }
            assertTrue(
                mangled.isNotEmpty(),
                "${type.simpleName} should declare mangled internal writeSealedAlreadyGuarded\$…",
            )
        }
    }

    @Test
    fun qaBypass_cmd9CannotPlaceClearAllFrameOn2bb1() = runTest {
        // Former attack: pass harmless cmd-9 plaintext while writing a CLEAR_ALL
        // sealed frame. Public API is plaintext-only, so CLEAR_ALL never reaches 2BB1.
        val fake = FakeBleTransport()
        fake.connect("fake-note-pro", GattProfiles.Plaud)
        val ble: BleTransport = fake
        val clearAll = rawAppCommand(ForbiddenOpcodes.CLEAR_ALL)
        val cmd9 = rawAppCommand(9)

        assertFailsWith<IllegalArgumentException> {
            ble.write(GattProfiles.PLAUD_WRITE, clearAll)
        }
        assertTrue(fake.recordedWrites.isEmpty(), "CLEAR_ALL must not reach 2BB1 via public write")

        val secret = testSecret()
        val session = PlaudBleTransport(fake).openEncryptedSession(secret)
        assertFailsWith<IllegalArgumentException> {
            session.writeEncrypted(clearAll)
        }
        assertTrue(fake.recordedWrites.isEmpty(), "CLEAR_ALL must not reach 2BB1 via writeEncrypted")

        // Allowed cmd 9 seals to ciphertext of cmd 9 — never a raw CLEAR_ALL frame.
        val seq = session.nextSendSequence
        session.writeEncrypted(cmd9)
        val onWire = fake.recordedWrites.single().second
        assertFalse(onWire.contentEquals(clearAll), "on-wire must not be raw CLEAR_ALL")
        val cipher = createSessionCipher()
        val opened = cipher.openPacket(secret, seq, onWire)
        assertTrue(opened != null && opened.second.contentEquals(cmd9))
        assertTrue(onWire.contentEquals(cipher.sealPacket(secret, seq, cmd9)))
    }

    @Test
    fun qaBypass_rawFe20LeBytesRejectedOn2bb1() = runTest {
        // Little-endian 0xFE20 on the wire is [0x20, 0xFE] (Bytes.u16Le / Framing).
        val fe20Le = byteArrayOf(0x20, 0xFE.toByte())
        assertEquals(ForbiddenOpcodes.FE20, Bytes.readU16Le(fe20Le, 0))

        val fake = FakeBleTransport()
        fake.connect("fake-note-pro", GattProfiles.Plaud)
        val ble: BleTransport = fake

        assertFailsWith<IllegalArgumentException> {
            ble.write(GattProfiles.PLAUD_WRITE, fe20Le)
        }
        assertTrue(fake.recordedWrites.isEmpty(), "raw FE20 LE must not reach 2BB1 via public write")

        val session = PlaudBleTransport(fake).openEncryptedSession(testSecret())
        assertFailsWith<IllegalArgumentException> {
            session.writeEncrypted(fe20Le)
        }
        assertTrue(fake.recordedWrites.isEmpty(), "raw FE20 LE must not reach 2BB1 via writeEncrypted")
    }

    @Test
    fun customCipherBypass_publicApiCannotInjectSealPacket() = runTest {
        // Document the attack the public API must block: a SessionCipher whose
        // sealPacket returns attacker-chosen bytes for allowed plaintext (cmd 9).
        val attackerBytes = byteArrayOf(0x01, 0x20, 0xFE.toByte())
        val malicious = object : SessionCipher {
            override fun sealPacket(
                secret: Protocol20SessionSecret,
                sequence: Long,
                packet: ByteArray,
            ): ByteArray = attackerBytes.copyOf()

            override fun openPacket(
                secret: Protocol20SessionSecret,
                sequence: Long,
                ciphertextAndTag: ByteArray,
            ): Pair<Long, ByteArray>? = null

            override fun rsaDecryptPkcs1(privateKeyPem: String, ciphertext: ByteArray): ByteArray =
                error("unused")

            override fun rsaEncryptPkcs1(publicKeyPem: String, plaintext: ByteArray): ByteArray =
                error("unused")

            override fun chacha20Poly1305Open(
                key: ByteArray,
                nonce: ByteArray,
                aad: ByteArray,
                ciphertextAndTag: ByteArray,
            ): ByteArray? = null

            override fun chacha20Poly1305Seal(
                key: ByteArray,
                nonce: ByteArray,
                aad: ByteArray,
                plaintext: ByteArray,
            ): ByteArray = error("unused")
        }

        val fake = FakeBleTransport()
        fake.connect("fake-note-pro", GattProfiles.Plaud)
        val secret = testSecret()
        val cmd9 = rawAppCommand(9)

        // Internal overload still exists for :ble tests — proves the bypass shape.
        val internalSession = PlaudBleTransport(fake).openEncryptedSession(
            cipher = malicious,
            secret = secret,
        )
        internalSession.writeEncrypted(cmd9)
        assertTrue(
            fake.recordedWrites.single().second.contentEquals(attackerBytes),
            "internal cipher overload can place attacker bytes (why it must stay internal)",
        )
        fake.clearRecordedWrites()

        // Public API builds createSessionCipher() inside :ble — on-wire equals real seal.
        val publicSession = PlaudBleTransport(fake).openEncryptedSession(secret)
        val seq = publicSession.nextSendSequence
        publicSession.writeEncrypted(cmd9)
        val onWire = fake.recordedWrites.single().second
        assertFalse(onWire.contentEquals(attackerBytes), "public path must not use injected cipher")
        val real = createSessionCipher().sealPacket(secret, seq, cmd9)
        assertTrue(onWire.contentEquals(real), "public path must seal with createSessionCipher()")
    }

    @Test
    fun writeEncrypted_failsAfterDisconnectUntilNewSession() = runTest {
        val fake = FakeBleTransport()
        fake.connect("fake-note-pro", GattProfiles.Plaud)
        val secret = testSecret()
        val oldSession = PlaudBleTransport(fake).openEncryptedSession(secret)
        val cmd9 = rawAppCommand(9)

        oldSession.writeEncrypted(cmd9)
        assertEquals(1, fake.recordedWrites.size)
        fake.clearRecordedWrites()

        fake.disconnect()
        fake.connect("fake-note-pro", GattProfiles.Plaud)

        assertFailsWith<IllegalStateException> {
            oldSession.writeEncrypted(cmd9)
        }
        assertTrue(fake.recordedWrites.isEmpty(), "invalidated session must not write")

        val newSession = PlaudBleTransport(fake).openEncryptedSession(secret)
        newSession.writeEncrypted(cmd9)
        assertEquals(1, fake.recordedWrites.size)
    }

    @Test
    fun openEncryptedSession_requiresInitialSendSeqAtLeast2() {
        val fake = FakeBleTransport()
        val secret = testSecret()
        assertFailsWith<IllegalArgumentException> {
            PlaudBleTransport(fake).openEncryptedSession(secret, initialSendSeq = 1)
        }
        assertFailsWith<IllegalArgumentException> {
            PlaudBleTransport(fake).openEncryptedSession(secret, initialSendSeq = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            PlaudBleTransport(fake).openEncryptedSession(
                cipher = createSessionCipher(),
                secret = secret,
                initialSendSeq = 1,
            )
        }
        // Boundary: 2 is allowed.
        PlaudBleTransport(fake).openEncryptedSession(secret, initialSendSeq = 2)
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
        val session = plaud.openEncryptedSession(testSecret())

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
        // Public path: cipher built inside :ble. Use matching createSessionCipher for verify.
        val session = PlaudBleTransport(fake).openEncryptedSession(
            secret = secret,
            initialSendSeq = 2,
        )

        val plaintext = rawAppCommand(9) // battery — allowed
        val expectedSeq = session.nextSendSequence
        session.writeEncrypted(plaintext)

        assertEquals(1, fake.recordedWrites.size)
        assertEquals(GattProfiles.PLAUD_WRITE, fake.recordedWrites.single().first)
        val onWire = fake.recordedWrites.single().second

        val opened = cipher.openPacket(secret, expectedSeq, onWire)
        assertTrue(opened != null, "on-wire bytes must open with createSessionCipher()")
        assertEquals(expectedSeq, opened!!.first)
        assertTrue(
            opened.second.contentEquals(plaintext),
            "on-wire seal must decrypt to the guarded plaintext",
        )
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
