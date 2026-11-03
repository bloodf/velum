package dev.velum.ble.wifi

import dev.velum.protocol.ForbiddenOpcodes
import dev.velum.protocol.ForbiddenSettings
import dev.velum.protocol.Framing
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest

/** #350: the Wi-Fi send path applies the same forbidden opcode / setting check as BLE writes. Synthetic only. */
class WifiSendGuardTest {
    private class RecordingServer : WifiTransferServer {
        val sent = mutableListOf<ByteArray>()
        override suspend fun start(port: Int, bindHost: String?, preamble: ByteArray) = Unit
        override suspend fun stop() = Unit
        override fun incomingEncryptedFrames(): Flow<ByteArray> = emptyFlow()
        override suspend fun sendEncryptedFrame(bytes: ByteArray) { sent += bytes }
    }

    private class CountingCipher : WifiFrameCipher {
        override val mode = WifiPayloadCipher.ChaCha20Poly1305
        val sequences = mutableListOf<Long>()
        override fun seal(sequence: Long, packet: ByteArray): ByteArray { sequences += sequence; return packet.copyOf() }
        override fun open(sequence: Long, ciphertextAndTag: ByteArray): Pair<Long, ByteArray>? = null
    }

    private val server = RecordingServer()
    private val cipher = CountingCipher()
    private val session = WifiFastTransferSession(UnsupportedWifiSoftApJoiner, server, cipher)

    private fun frames(opcode: Int): List<ByteArray> = buildList {
        add(byteArrayOf(Framing.APP_PREFIX, (opcode and 0xFF).toByte(), (opcode shr 8).toByte())) // [01, lo, hi]
        add(byteArrayOf((opcode and 0xFF).toByte(), (opcode shr 8).toByte()))                     // bare u16le
        if (opcode <= 0xFF) add(byteArrayOf(Framing.APP_PREFIX, opcode.toByte()))                 // trimmed [01, cmd]
    }

    @Test fun every_forbidden_opcode_is_refused_before_sealing_in_each_framing() = runTest {
        for (opcode in ForbiddenOpcodes.ALL) for (frame in frames(opcode)) {
            assertFailsWith<IllegalArgumentException>("opcode $opcode ${frame.toList()}") { session.sendPacket(frame) }
        }
        assertTrue(server.sent.isEmpty(), "nothing reaches the socket")
        assertTrue(cipher.sequences.isEmpty(), "no sequence number is used")
    }

    @Test fun cmd_8_writes_to_forbidden_settings_are_refused() = runTest {
        for (setting in listOf(ForbiddenSettings.AUTO_DELETE, ForbiddenSettings.AUTO_SYNC)) {
            val frame = byteArrayOf(Framing.APP_PREFIX, 8, 0, setting.toByte(), 1)
            assertFailsWith<IllegalArgumentException>("setting $setting") { session.sendPacket(frame) }
        }
        assertTrue(server.sent.isEmpty())
    }

    @Test fun an_allowed_packet_still_goes_out_and_a_refused_one_does_not_skip_the_sequence() = runTest {
        assertFailsWith<IllegalArgumentException> { session.sendPacket(frames(ForbiddenOpcodes.OTA).first()) }
        val ok = Framing.encodeCommand(0x40)
        session.sendPacket(ok)
        assertEquals(listOf(0L), cipher.sequences)
        assertContentEquals(ok, server.sent.single())
    }
}
