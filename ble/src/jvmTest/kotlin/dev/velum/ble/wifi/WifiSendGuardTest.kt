package dev.velum.ble.wifi

import dev.velum.protocol.ForbiddenOpcodes
import dev.velum.protocol.ForbiddenSettings
import dev.velum.protocol.Framing
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest

/** #350: the Wi-Fi send path applies the same forbidden opcode / setting check as BLE writes. Synthetic only. */
class WifiSendGuardTest {
    private object NoopJoiner : WifiSoftApJoiner {
        override suspend fun join(ssid: String, password: String, ssidIsPattern: Boolean) = Unit
        override suspend fun leave() = Unit
    }

    private class RecordingServer : WifiTransferServer {
        val sent = mutableListOf<ByteArray>()
        override suspend fun start(port: Int, bindHost: String?, preamble: ByteArray) = Unit
        override suspend fun stop() = Unit
        override fun incomingEncryptedFrames(): Flow<ByteArray> = emptyFlow()
        var firstDelayMs = 0L
        private var calls = 0
        override suspend fun sendEncryptedFrame(bytes: ByteArray) {
            if (calls++ == 0 && firstDelayMs > 0) delay(firstDelayMs)
            sent += bytes
        }
    }

    private class CountingCipher : WifiFrameCipher {
        override val mode = WifiPayloadCipher.ChaCha20Poly1305
        val sequences = mutableListOf<Long>()
        var beforeSeal: () -> Unit = {}
        override fun seal(sequence: Long, packet: ByteArray): ByteArray {
            beforeSeal()
            sequences += sequence
            return packet.copyOf()
        }
        override fun open(sequence: Long, ciphertextAndTag: ByteArray): Pair<Long, ByteArray>? = null
    }

    private val server = RecordingServer()
    private val cipher = CountingCipher()
    private val session = WifiFastTransferSession(NoopJoiner, server, cipher)

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

    @Test fun concurrent_sends_go_out_in_sequence_order_with_no_sequence_reused() = runTest {
        // The first frame is slow to write; without one-at-a-time sending a later frame overtakes it on the wire.
        server.firstDelayMs = 50
        val packets = (0 until 5).map { Framing.encodeCommand(0x40 + it) }
        packets.map { async { session.sendPacket(it) } }.awaitAll()
        assertEquals((0L until 5L).toList(), cipher.sequences)
        assertEquals(packets.map { it.toList() }, server.sent.map { it.toList() }, "wire order = sequence order")
    }

    @Test fun the_guard_checks_the_bytes_that_are_sent() = runTest {
        val packet = Framing.encodeCommand(0x40)
        // A caller that rewrites its array to a forbidden opcode between the guard and the seal.
        cipher.beforeSeal = { packet[1] = ForbiddenOpcodes.OTA.toByte() }
        session.sendPacket(packet)
        assertContentEquals(Framing.encodeCommand(0x40), server.sent.single(), "the checked bytes are the sent bytes")
    }

    @Test fun restarting_the_session_restarts_the_sequence_at_zero() = runTest {
        session.sendPacket(Framing.encodeCommand(0x40))
        session.sendPacket(Framing.encodeCommand(0x41))
        session.start("ssid", "password")
        session.sendPacket(Framing.encodeCommand(0x42))
        assertEquals(listOf(0L, 1L, 0L), cipher.sequences)
    }
}
