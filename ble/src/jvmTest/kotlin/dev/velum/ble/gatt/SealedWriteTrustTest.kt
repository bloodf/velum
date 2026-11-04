package dev.velum.ble.gatt

import dev.velum.ble.client.PlaudDeviceClient
import dev.velum.ble.crypto.createSessionCipher
import dev.velum.ble.crypto.rsaKeyPairToPem
import dev.velum.ble.fake.FakeBleTransport
import dev.velum.ble.fake.FakePlaudPeripheral
import dev.velum.protocol.Commands
import dev.velum.protocol.DeviceIdentityKeys
import dev.velum.protocol.Protocol20SessionSecret
import dev.velum.protocol.SessionCipher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest

/**
 * #19 (QA probes, re-run on main): bytes that reach the radio must be the bytes the guard checked, even when the
 * [SessionCipher] handed to the client is broken or seals something else, and a closed client must not write on a later connection.
 * Synthetic keys only.
 */
class SealedWriteTrustTest {
    private val pems = rsaKeyPairToPem()
    private val keys = DeviceIdentityKeys(
        serialNumber = "881SYNTH",
        serialSignature = ByteArray(256) { (it % 251).toByte() },
        publicKeyPem = pems.first,
        privateKeyPem = pems.second,
        bindToken = "synthetic",
    )
    private val real = createSessionCipher()

    /** Counts the writes that reach the transport (FakeBleTransport runs this hook before each one). */
    private class Wire(val ble: FakeBleTransport) {
        var writes = 0
        init { ble.beforeWrite = { writes++ } }
    }

    private suspend fun connected(cipher: SessionCipher): Pair<PlaudDeviceClient, Wire> {
        val peripheral = FakePlaudPeripheral(real, keys, ByteArray(10), requirePublicKey = false)
        val ble = FakeBleTransport(peripheral)
        ble.connect("fake-note-pro")
        val client = PlaudDeviceClient(ble, cipher, keys, responseTimeout = 5.seconds)
        client.unlock()
        return client to Wire(ble)
    }

    /**
     * A cipher whose seal is valid for the session (right key, nonce and sequence) but carries [other] instead of the
     * packet the guard checked: the case a length or tag check cannot catch.
     */
    private fun cipherSealing(other: ByteArray, onSeal: (Long) -> Unit = {}, only: Int? = null): SessionCipher =
        object : SessionCipher by real {
            var calls = 0
            override fun sealPacket(secret: Protocol20SessionSecret, sequence: Long, packet: ByteArray): ByteArray {
                onSeal(sequence)
                val use = sequence >= FIRST_COMMAND_SEQ && (only == null || calls++ < only)
                return real.sealPacket(secret, sequence, if (use) other else packet)
            }
        }

    @Test fun a_validly_sealed_forbidden_packet_cannot_reach_the_wire() = runTest {
        val forbidden = listOf(
            "FE20 little endian" to byteArrayOf(0x01, 0x20, 0xFE.toByte()),
            "write to setting 4" to byteArrayOf(0x01, 0x08, 0x00, 0x04, 0x01),
            "write to setting 26" to byteArrayOf(0x01, 0x08, 0x00, 0x1A, 0x01),
            "opcode 5" to byteArrayOf(0x01, 0x05),
        )
        for ((name, other) in forbidden) {
            val (client, wire) = connected(cipherSealing(other))
            val before = wire.writes
            assertFailsWith<IllegalStateException>(name) { client.writeCommandForTest(Commands.BATTERY_LIKELY) }
            assertEquals(before, wire.writes, "$name must not reach the wire")
            client.close()
        }
    }

    @Test fun a_cipher_that_edits_its_input_then_seals_the_edit_is_refused() = runTest {
        val editing = object : SessionCipher by real {
            override fun sealPacket(secret: Protocol20SessionSecret, sequence: Long, packet: ByteArray): ByteArray {
                if (sequence >= FIRST_COMMAND_SEQ) { packet[1] = 0x20; packet[2] = 0xFE.toByte() } // becomes FE20
                return real.sealPacket(secret, sequence, packet)
            }
        }
        val (client, wire) = connected(editing)
        val before = wire.writes
        assertFailsWith<IllegalStateException> { client.writeCommandForTest(Commands.BATTERY_LIKELY, byteArrayOf(1, 2, 3)) }
        assertEquals(before, wire.writes)
        client.close()
    }

    @Test fun a_cipher_returning_garbage_is_refused_too() = runTest {
        val garbage = object : SessionCipher by real {
            override fun sealPacket(secret: Protocol20SessionSecret, sequence: Long, packet: ByteArray) =
                if (sequence >= FIRST_COMMAND_SEQ) byteArrayOf(1, 32, -2, 0) else real.sealPacket(secret, sequence, packet)
        }
        val (client, wire) = connected(garbage)
        val before = wire.writes
        assertFailsWith<IllegalStateException> { client.writeCommandForTest(Commands.BATTERY_LIKELY) }
        assertEquals(before, wire.writes)
        client.close()
    }

    @Test fun a_cipher_that_keeps_and_edits_its_output_cannot_change_the_bytes_written() = runTest {
        var kept: ByteArray? = null
        val retaining = object : SessionCipher by real {
            override fun sealPacket(secret: Protocol20SessionSecret, sequence: Long, packet: ByteArray): ByteArray =
                real.sealPacket(secret, sequence, packet).also { if (sequence >= FIRST_COMMAND_SEQ) kept = it }
        }
        val peripheral = FakePlaudPeripheral(real, keys, ByteArray(10), requirePublicKey = false)
        val ble = FakeBleTransport(peripheral)
        ble.connect("fake-note-pro")
        val client = PlaudDeviceClient(ble, retaining, keys, responseTimeout = 5.seconds)
        client.unlock()
        // After the client's own check and before the radio: the cipher scribbles on the array it returned.
        ble.beforeWrite = { kept?.fill(0x7F) }
        val before = peripheral.receivedCommands.size
        client.writeCommandForTest(Commands.BATTERY_LIKELY)
        assertEquals(before + 1, peripheral.receivedCommands.size, "the peripheral must still open the checked packet")
        assertEquals(Commands.BATTERY_LIKELY, peripheral.receivedCommands.last())
        client.close()
    }

    @Test fun a_refused_seal_does_not_advance_the_counter() = runTest {
        val seen = mutableListOf<Long>()
        // Only the first sealed command is swapped for a forbidden one; the second is sealed honestly.
        val (client, wire) = connected(cipherSealing(byteArrayOf(0x01, 0x20, 0xFE.toByte()), { seen += it }, only = 1))
        val before = wire.writes
        assertFailsWith<IllegalStateException> { client.writeCommandForTest(Commands.BATTERY_LIKELY) }
        client.writeCommandForTest(Commands.BATTERY_LIKELY)
        assertEquals(before + 1, wire.writes, "only the second write reaches the wire")
        assertEquals(listOf(FIRST_COMMAND_SEQ, FIRST_COMMAND_SEQ), seen.takeLast(2), "the second seal reuses the same counter")
        client.close()
    }

    @Test fun the_real_cipher_still_writes_allowed_commands() = runTest {
        val (client, wire) = connected(real)
        val before = wire.writes
        client.writeCommandForTest(Commands.BATTERY_LIKELY)
        assertEquals(before + 1, wire.writes)
        client.close()
    }

    @Test fun a_closed_client_cannot_write_even_after_the_link_comes_back() = runTest {
        val (client, wire) = connected(real)
        client.close()
        wire.ble.disconnect()
        wire.ble.connect("fake-note-pro") // the same device reconnects; the old session secret must not be reused
        val before = wire.writes
        assertFailsWith<IllegalStateException> { client.writeCommandForTest(Commands.BATTERY_LIKELY) }
        assertEquals(before, wire.writes, "a stale session wrote ${wire.writes - before} packet(s)")
    }

    @Test fun an_unclosed_client_cannot_write_after_the_link_was_remade() = runTest {
        val (client, wire) = connected(real)
        client.writeCommandForTest(Commands.BATTERY_LIKELY) // fine while the link it unlocked on is up
        wire.ble.disconnect()
        wire.ble.connect("fake-note-pro") // same device, new link: the old secret and counter are void
        val before = wire.writes
        val refused = assertFailsWith<IllegalStateException> { client.writeCommandForTest(Commands.BATTERY_LIKELY) }
        assertEquals("Link changed since unlock", refused.message)
        assertEquals(before, wire.writes, "a stale client wrote ${wire.writes - before} packet(s)")
        client.close()
    }

    @Test fun a_client_unlocked_again_on_the_new_link_writes_normally() = runTest {
        val (client, wire) = connected(real)
        wire.ble.disconnect()
        wire.ble.connect("fake-note-pro")
        client.unlock() // a fresh handshake on the new link is allowed
        val before = wire.writes
        client.writeCommandForTest(Commands.BATTERY_LIKELY)
        assertEquals(before + 1, wire.writes)
        client.close()
    }

    @Test fun a_closed_client_cannot_unlock_again_and_hides_its_secret() = runTest {
        val (client, wire) = connected(real)
        assertTrue(client.sessionSecret != null)
        client.close()
        assertEquals(null, client.sessionSecret)
        wire.ble.disconnect()
        wire.ble.connect("fake-note-pro")
        val before = wire.writes
        assertFailsWith<IllegalStateException> { client.unlock() }
        assertEquals(before, wire.writes, "unlock after close must write nothing")
    }

    private companion object {
        /** Unlock uses plain writes; the first sealed command goes out at counter 2. */
        const val FIRST_COMMAND_SEQ = 2L
    }
}
