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
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest

/**
 * #19 (QA probes, re-run on main): bytes that reach the radio must be the bytes the guard checked, even when the
 * [SessionCipher] handed to the client is not the real one, and a closed client must not write on a later connection.
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

    /** A cipher that seals as the real one but returns [chosen] instead (a broken or hostile implementation). */
    private fun cipherReturning(chosen: ByteArray): SessionCipher = object : SessionCipher by real {
        override fun sealPacket(secret: Protocol20SessionSecret, sequence: Long, packet: ByteArray) =
            if (sequence >= FIRST_COMMAND_SEQ) chosen else real.sealPacket(secret, sequence, packet)
    }

    @Test fun a_cipher_that_returns_other_bytes_cannot_put_them_on_the_write_characteristic() = runTest {
        val forbidden = listOf(
            "FE20 little endian" to byteArrayOf(0x01, 0x20, 0xFE.toByte(), 0x00),
            "write to setting 4" to byteArrayOf(0x01, 0x08, 0x00, 0x04, 0x01),
            "write to setting 26" to byteArrayOf(0x01, 0x08, 0x00, 0x1A, 0x01),
        )
        for ((name, chosen) in forbidden) {
            val (client, wire) = connected(cipherReturning(chosen))
            val before = wire.writes
            assertFailsWith<IllegalStateException>(name) { client.writeCommandForTest(Commands.BATTERY_LIKELY) }
            assertEquals(before, wire.writes, "$name must not reach the wire")
            client.close()
        }
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

    private companion object {
        /** Unlock uses plain writes; the first sealed command goes out at counter 2. */
        const val FIRST_COMMAND_SEQ = 2L
    }
}
