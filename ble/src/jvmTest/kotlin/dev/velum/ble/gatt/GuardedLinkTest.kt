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
import kotlin.test.assertTrue
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

/** A wrapper that edits the bytes it is handed, then forwards the same write (the "edit after the guard" bypass). */
private class EditingTransport(private val stub: StubBleTransport = StubBleTransport()) : BleTransport by stub {
    /** The write exactly as a transport behind this wrapper would receive it. */
    var forwarded: GattWrite? = null

    override suspend fun writeRaw(write: GattWrite) {
        write.bytes[1] = 0x20
        write.bytes[2] = 0xFE.toByte()
        forwarded = write
    }
}

class GuardedLinkTest {
    private val char = GattProfiles.PLAUD_WRITE

    private fun prefixed(opcode: Int, params: ByteArray = ByteArray(0)): ByteArray =
        byteArrayOf(Framing.APP_PREFIX) + Bytes.u16Le(opcode) + params

    private fun bare(opcode: Int, params: ByteArray = ByteArray(0)): ByteArray =
        Bytes.u16Le(opcode) + params

    private fun rig() = RecordingTransport().let { it to GuardedLink(it, GattProfiles.Plaud) }

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

    /** #326: 30 is delete-file, receive-only. Every form a write can take must be rejected, plain or sealed. */
    @Test
    fun deleteFile_30_isRejected_inEveryForm_andNeverReachesTransport() = runTest {
        val (t, link) = rig()
        var sealed = 0
        val forms = listOf(
            prefixed(30, Bytes.u32Le(1_700_000_000L)),
            prefixed(30),
            byteArrayOf(Framing.APP_PREFIX, 30), // what Framing's zero-trimming makes of a param-less 30
            bare(30),
        )
        for (packet in forms) {
            assertFailsWith<IllegalArgumentException>(packet.toList().toString()) {
                link.write(char, packet, withoutResponse = false)
            }
            assertFailsWith<IllegalArgumentException>(packet.toList().toString()) {
                link.write(char, packet, withoutResponse = false) { sealed++; it }
            }
        }
        assertEquals(0, sealed, "seal must not run for a rejected delete")
        assertEquals(0, t.writes.size)
    }

    @Test
    fun fe20_byteOrder_isLittleEndian() = runTest {
        val (t, link) = rig()
        // 0xFE20 on the wire is 20 FE (LE); the swapped 0x20FE is a different, allowed opcode.
        assertFailsWith<IllegalArgumentException> {
            link.write(char, byteArrayOf(0x20, 0xFE.toByte()), withoutResponse = false)
        }
        // The swapped bytes are a different opcode but not a shape production sends on 2BB1 (deny by default): refused too.
        assertFailsWith<IllegalArgumentException> {
            link.write(char, byteArrayOf(0xFE.toByte(), 0x20), withoutResponse = false)
        }
        // An unlock chunk (0xFE10, little endian) is the bare shape that does go out.
        link.write(char, byteArrayOf(0x10, 0xFE.toByte(), 1, 0, 9), withoutResponse = false)
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
        // GuardedLink itself never parses sealed bytes (its seal overload is internal to :ble; PlaudDeviceClient verifies
        // that the sealed bytes open to the checked plaintext). Ciphertext that happens to start with forbidden opcode
        // 104 must still go out.
        link.write(char, prefixed(0x1234), withoutResponse = false) { bare(ForbiddenOpcodes.CLEAR_ALL) + it }
        val w = t.writes.single()
        assertIs<SealedWrite>(w)
        assertContentEquals(bare(ForbiddenOpcodes.CLEAR_ALL) + prefixed(0x1234), w.bytes)
    }

    @Test
    fun forbiddenSettings_rejectedForReadsAndWrites_plainAndSealed() = runTest {
        val (t, link) = rig()
        for (id in ForbiddenSettings.ALL) {
            for (params in listOf(byteArrayOf(id.toByte(), 1), byteArrayOf(id.toByte()))) {
                for (frame in listOf(prefixed(CommonSettings.CMD, params), bare(CommonSettings.CMD, params))) {
                    assertFailsWith<IllegalArgumentException> { link.write(char, frame, false) }
                    assertFailsWith<IllegalArgumentException> { link.write(char, frame, false) { it } }
                }
            }
        }
        assertEquals(0, t.writes.size)
    }

    @Test
    fun otherSettingsAndParamlessCmd8_stillPass() = runTest {
        val (t, link) = rig()
        link.write(char, prefixed(CommonSettings.CMD, byteArrayOf(1)), false)
        link.write(char, prefixed(CommonSettings.CMD, byteArrayOf(1, 0x0A)), false)
        link.write(char, prefixed(CommonSettings.CMD), false)
        assertEquals(3, t.writes.size)
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

    @Test
    fun trimTrailingZeros_ofEveryForbiddenOpcode_isRejected() = runTest {
        val (t, link) = rig()
        for (op in ForbiddenOpcodes.ALL) {
            val wire = Bytes.trimTrailingZeros(prefixed(op))
            assertFailsWith<IllegalArgumentException>("opcode $op as ${wire.toList()}") {
                link.write(char, wire, withoutResponse = false)
            }
        }
        assertEquals(0, t.writes.size)
    }

    /**
     * A forbidden-setting write whose value is 0 is trimmed to [settingId] by Framing.encodeCommand, i.e.
     * byte-identical to a read. That is why the guard rejects both shapes rather than only writes.
     */
    @Test
    fun forbiddenSettingWriteWithValueZero_trimsToReadShape_andIsRejected() = runTest {
        val (t, link) = rig()
        for (id in ForbiddenSettings.ALL) {
            val trimmed = Bytes.trimTrailingZeros(prefixed(CommonSettings.CMD, byteArrayOf(id.toByte(), 0)))
            assertContentEquals(prefixed(CommonSettings.CMD, byteArrayOf(id.toByte())), trimmed)
            assertFailsWith<IllegalArgumentException> { link.write(char, trimmed, withoutResponse = false) }
        }
        assertEquals(0, t.writes.size)
    }

    @Test
    fun callerMutationDuringOrAfterCheck_neverReachesTransport() = runTest {
        for (sealed in listOf(false, true)) {
            val t = RecordingTransport()
            val caller = byteArrayOf(Framing.APP_PREFIX, 0x40, 1, 2, 3)
            var checkedSnapshot: ByteArray? = null
            // The guard approves the bytes it sees, then (maliciously) mutates the caller's array while still inside check.
            val guard = WriteGuard { checked ->
                checkedSnapshot = checked.copyOf()
                caller[2] = 0x7F
                caller[3] = 0x7F
            }
            // A characteristic of its own: on 2BB1 the profile's guard is ignored (PlaudWriteGuard always applies).
            val probe = GattChar("probe-service", "probe-char")
            val link = GuardedLink(t, GattProfile("probe", emptyList(), mapOf(probe to guard)))
            if (sealed) link.write(probe, caller, false) { it } else link.write(probe, caller, false)
            caller[4] = 0x7F // and after the call returns
            val sent = t.writes.single().bytes
            assertContentEquals(checkedSnapshot, sent, "sealed=$sealed: transport must get exactly the bytes that were checked")
            assertContentEquals(byteArrayOf(Framing.APP_PREFIX, 0x40, 1, 2, 3), sent)
        }
    }

    private val fe20Frame = byteArrayOf(Framing.APP_PREFIX, 0x20, 0xFE.toByte())

    @Test fun a_profile_with_a_do_nothing_guard_on_2BB1_still_gets_the_plaud_guard() = runTest {
        val wire = RecordingTransport()
        val weak = GuardedLink(wire, GattProfile("weak", emptyList(), mapOf(char to WriteGuard { })))
        assertFailsWith<IllegalArgumentException> { weak.write(char, fe20Frame, withoutResponse = false) }
        assertFailsWith<IllegalArgumentException> { weak.write(char, fe20Frame, withoutResponse = false) { it } }
        assertEquals(0, wire.writes.size)
    }

    @Test fun frames_that_are_not_production_shapes_are_refused_on_2BB1() = runTest {
        val wire = RecordingTransport()
        val link = GuardedLink(wire, GattProfiles.Plaud)
        for (op in ForbiddenOpcodes.ALL.filter { it < 256 }) {
            for (first in listOf(0x00, 0x02, 0x03)) {
                assertFailsWith<IllegalArgumentException>("[%02x, %02x]".format(first, op)) {
                    link.write(char, byteArrayOf(first.toByte(), op.toByte()), withoutResponse = false)
                }
            }
        }
        // Even an allowed opcode (cmd 9) is refused in a bare shape; only the prefixed command frame and unlock chunks go out.
        for (first in listOf(0x00, 0x02)) {
            assertFailsWith<IllegalArgumentException> { link.write(char, byteArrayOf(first.toByte(), 0x09), withoutResponse = false) }
        }
        assertEquals(0, wire.writes.size)
        link.write(char, byteArrayOf(Framing.APP_PREFIX, 0x09), withoutResponse = false)
        for (unlock in listOf(0xFE10, 0xFE11, 0xFE12)) {
            link.write(char, byteArrayOf(unlock.toByte(), (unlock shr 8).toByte(), 1, 0, 7), withoutResponse = false)
        }
        assertEquals(4, wire.writes.size)
    }


    @Test fun a_wrapper_editing_write_bytes_cannot_change_what_the_next_transport_sees() = runTest {
        for (sealed in listOf(false, true)) {
            val t = EditingTransport()
            val link = GuardedLink(t, GattProfiles.Plaud)
            val allowed = byteArrayOf(Framing.APP_PREFIX, 0x09, 0x00, 0x07)
            if (sealed) link.write(char, allowed, false) { it } else link.write(char, allowed, false)
            val next = checkNotNull(t.forwarded)
            assertContentEquals(allowed, next.bytes, "sealed=$sealed: the edit must not reach the next transport")
            requireTransportAllowed(next)
        }
    }

    @Test fun an_uppercase_2BB1_with_a_do_nothing_guard_still_gets_the_plaud_guard() = runTest {
        val upper = GattChar(char.service.uppercase(), char.characteristic.uppercase())
        val wire = RecordingTransport()
        val weak = GuardedLink(wire, GattProfile("weak", emptyList(), mapOf(upper to WriteGuard { })))
        assertFailsWith<IllegalArgumentException> { weak.write(upper, fe20Frame, withoutResponse = false) }
        assertFailsWith<IllegalArgumentException> { weak.write(upper, fe20Frame, withoutResponse = false) { it } }
        assertEquals(0, wire.writes.size)
        assertTrue(upper.isPlaudWrite)
        assertTrue(!GattChar("other", "other").isPlaudWrite)
    }
}
