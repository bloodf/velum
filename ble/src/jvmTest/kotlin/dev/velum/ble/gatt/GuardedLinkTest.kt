package dev.velum.ble.gatt

import dev.velum.ble.BleTransport
import dev.velum.ble.StubBleTransport
import dev.velum.protocol.Bytes
import dev.velum.protocol.CommonSettings
import dev.velum.protocol.ForbiddenOpcodes
import dev.velum.protocol.ForbiddenSettings
import dev.velum.protocol.Framing
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest

/** Records what reaches the radio; delegates everything else to the stub. */
private class RecordingTransport(
    private val stub: StubBleTransport = StubBleTransport(),
) : BleTransport by stub {
    val writes = mutableListOf<GattWrite>()

    override suspend fun writeRaw(write: GattWrite) {
        writes += write
    }
}

class GuardedLinkTest {
    private val char = GattProfiles.PLAUD_WRITE

    private fun prefixed(opcode: Int, params: ByteArray = ByteArray(0)): ByteArray =
        byteArrayOf(Framing.APP_PREFIX) + Bytes.u16Le(opcode) + params

    private fun bare(opcode: Int, params: ByteArray = ByteArray(0)): ByteArray =
        Bytes.u16Le(opcode) + params

    private fun rig() = RecordingTransport().let { it to GuardedLink(it) }

    @Test
    fun forbiddenOpcodes_rejectedPlain_prefixedAndBare_neverReachTransport() = runTest {
        val (t, link) = rig()
        for (op in ForbiddenOpcodes.ALL) {
            for (packet in listOf(prefixed(op), bare(op))) {
                assertFailsWith<IllegalArgumentException>("opcode $op") {
                    link.write(char, packet, withoutResponse = false)
                }
            }
        }
        assertEquals(0, t.writes.size)
    }

    @Test
    fun fe20_byteOrder_isLittleEndian() = runTest {
        val (t, link) = rig()
        // 0xFE20 on the wire is 20 FE (LE); the swapped 0x20FE is a different, allowed opcode.
        assertFailsWith<IllegalArgumentException> {
            link.write(char, byteArrayOf(0x20, 0xFE.toByte()), withoutResponse = false)
        }
        link.write(char, byteArrayOf(0xFE.toByte(), 0x20), withoutResponse = false)
        assertEquals(1, t.writes.size)
    }

    @Test
    fun forbiddenOpcodes_rejectedBeforeSealing_encryptedSession() = runTest {
        val (t, link) = rig()
        var sealed = 0
        for (op in ForbiddenOpcodes.ALL) {
            assertFailsWith<IllegalArgumentException>("opcode $op") {
                link.write(char, prefixed(op), withoutResponse = false) { sealed++; it }
            }
        }
        assertEquals(0, sealed, "seal (and its sequence counter) must not run for rejected plaintext")
        assertEquals(0, t.writes.size)
    }

    @Test
    fun sealedBytes_areNeverParsedAsOpcodes() = runTest {
        val (t, link) = rig()
        // Ciphertext that happens to start with forbidden opcode 104 must still go out.
        link.write(char, prefixed(0x1234), withoutResponse = false) { bare(ForbiddenOpcodes.CLEAR_ALL) + it }
        val w = t.writes.single()
        assertIs<SealedWrite>(w)
        assertContentEquals(bare(ForbiddenOpcodes.CLEAR_ALL) + prefixed(0x1234), w.bytes)
    }

    @Test
    fun forbiddenSettingWrites_rejected_readsAllowed_plainAndSealed() = runTest {
        val (t, link) = rig()
        for (id in ForbiddenSettings.ALL) {
            val write = prefixed(CommonSettings.CMD, byteArrayOf(id.toByte(), 1))
            assertFailsWith<IllegalArgumentException> { link.write(char, write, false) }
            assertFailsWith<IllegalArgumentException> { link.write(char, write, false) { it } }
            // Read of the same setting is [settingId] only.
            link.write(char, prefixed(CommonSettings.CMD, byteArrayOf(id.toByte())), false)
        }
        assertEquals(ForbiddenSettings.ALL.size, t.writes.size)
    }

    @Test
    fun guardIsKeyedOnCharacteristic_notProfileId() = runTest {
        val t = RecordingTransport()
        val renamed = GuardedLink(t, GattProfile("anything", emptyList(), mapOf(char to PlaudWriteGuard)))
        assertFailsWith<IllegalArgumentException> {
            renamed.write(char, prefixed(ForbiddenOpcodes.OTA), false)
        }
    }

    @Test
    fun unguardedCharacteristic_isRejected() = runTest {
        val (t, link) = rig()
        val other = GattChar(char.service, "00002bb9-0000-1000-8000-00805f9b34fb")
        assertFailsWith<IllegalArgumentException> { link.write(other, prefixed(0x1234), false) }
        assertEquals(0, t.writes.size)
    }

    @Test
    fun plaudProfile_usesShippedUuids() {
        assertEquals("00002bb1-0000-1000-8000-00805f9b34fb", GattProfiles.PLAUD_WRITE.characteristic)
        assertEquals("00002bb0-0000-1000-8000-00805f9b34fb", GattProfiles.PLAUD_NOTIFY.characteristic)
        assertEquals(setOf(GattProfiles.PLAUD_WRITE), GattProfiles.Plaud.writes.keys)
    }

    @Test
    fun trimmedSingleByteOpcodes_areRejected_asEncodeCommandWouldSendThem() = runTest {
        val (t, link) = rig()
        // Framing.encodeCommand trims trailing zeros: cmd 5 / 104 / 128 / 143 go out as [01, cmd].
        val handBuilt = listOf(0x05, 0x68, 0x80, 0x8F).map { byteArrayOf(Framing.APP_PREFIX, it.toByte()) }
        for (packet in handBuilt) {
            assertFailsWith<IllegalArgumentException>("trimmed ${packet.toList()}") {
                link.write(char, packet, withoutResponse = false)
            }
            assertFailsWith<IllegalArgumentException>("trimmed sealed ${packet.toList()}") {
                link.write(char, packet, withoutResponse = false) { it }
            }
        }
        // Same shape produced by the real encoder for every single-byte forbidden opcode: the encoder
        // itself refuses forbidden opcodes, so prove the wire shape with an allowed neighbour instead.
        val wire = Framing.encodeCommand(0x40)
        assertContentEquals(byteArrayOf(Framing.APP_PREFIX, 0x40), wire)
        assertEquals(0, t.writes.size)
    }

    @Test
    fun trimmedSingleByteOpcodes_allowedCounterparts_pass() = runTest {
        val (t, link) = rig()
        for (op in listOf(0x04, 0x67, 0x7F, 0x8E, 0x40)) {
            link.write(char, byteArrayOf(Framing.APP_PREFIX, op.toByte()), withoutResponse = false)
        }
        assertEquals(5, t.writes.size)
    }

    @Test
    fun unlockChunkShapes_stillPass() = runTest {
        val (t, link) = rig()
        for (chunk in dev.velum.protocol.Unlock.encodeSnSignatureChunks(ByteArray(256) { 1 })) {
            link.write(char, chunk, withoutResponse = true)
        }
        assertEquals(true, t.writes.isNotEmpty())
    }
}
