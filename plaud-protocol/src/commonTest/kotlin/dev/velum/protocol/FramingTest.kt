package dev.velum.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FramingTest {
    @Test
    fun encodeCommand_handshakePrefix() {
        val frame = Framing.encodeCommand(Commands.HANDSHAKE, byteArrayOf(0x02, 0x00, 0x00))
        assertEquals(0x01, frame[0].toInt() and 0xFF)
        assertEquals(Commands.HANDSHAKE, Bytes.readU16Le(frame, 1))
    }

    @Test
    fun padBindToken_protocol20_is32Chars() {
        val padded = Framing.padBindToken("abc", 20)
        assertEquals(32, padded.size)
        assertEquals("abc" + "0".repeat(29), padded.decodeToString())
    }

    @Test
    fun decodeInbound_handshakeFixture() {
        // Observed: 01 01 00 00 14 00 05 1e 01 01 00 01 56 00 07 01
        val raw = Bytes.hexToBytes("010100001400051e0101000156000701")
        val packet = Framing.decodeInbound(raw)
        assertIs<InboundPacket.CommandResponse>(packet)
        assertEquals(Commands.HANDSHAKE, packet.cmd)
        val hs = HandshakeResponse.parse(packet.payload)
        assertEquals(HandshakeStatus.Ok, hs.status)
        assertEquals(20, hs.protocolVersion)
        assertEquals(5, hs.timezoneHours)
        assertEquals(0x1e, hs.timezoneMinutes)
        assertEquals(1, hs.audioChannels)
        assertTrue(hs.wifiSupported)
        assertTrue(hs.oggAudio)
    }

    @Test
    fun decodeInbound_batteryFixture() {
        val raw = Bytes.hexToBytes("010900005d")
        val packet = Framing.decodeInbound(raw)
        assertIs<InboundPacket.CommandResponse>(packet)
        assertEquals(Commands.BATTERY_LIKELY, packet.cmd)
        val battery = BatteryReading.parse(packet.payload)
        assertEquals(0x5d, battery.percent)
    }

    @Test
    fun decodeInbound_cmd141Fixture() {
        val raw = Bytes.hexToBytes("018d0000000000")
        val packet = Framing.decodeInbound(raw)
        assertIs<InboundPacket.CommandResponse>(packet)
        assertEquals(Commands.UNKNOWN_UNSOLICITED, packet.cmd)
    }

    @Test
    fun trimTrailingZeros_keepsPrefixByte() {
        val trimmed = Bytes.trimTrailingZeros(byteArrayOf(0x01, 0x02, 0x00, 0x00))
        assertTrue(trimmed.contentEquals(byteArrayOf(0x01, 0x02)))
    }
}
