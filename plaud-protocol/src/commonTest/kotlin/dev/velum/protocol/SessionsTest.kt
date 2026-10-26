package dev.velum.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SessionsTest {
    @Test
    fun parseSessionList_emptyFixture() {
        // Payload after type+cmd: b19cdb6a 0000 0000  (echoed now, total 0, index 0)
        // Full frame documented as: 011a00 b19cdb6a 0000 0000
        val raw = Bytes.hexToBytes("011a00b19cdb6a00000000")
        val packet = Framing.decodeInbound(raw)
        assertIs<InboundPacket.CommandResponse>(packet)
        assertEquals(Commands.GET_SESSIONS, packet.cmd)
        val list = Sessions.parseSessionList(packet.payload, protocolVersion = 20)
        assertEquals(0x6adb9cb1L, list.echoedNow)
        assertEquals(0, list.total)
        assertEquals(0, list.firstIndex)
        assertTrue(list.entries.isEmpty())
    }

    @Test
    fun parseSessionList_oneEntry_protocol20() {
        val sessionId = 0x6adb9df4L
        val size = 34816L
        val payload = Bytes.u32Le(0x6adb9cb1L) +
            Bytes.u16Le(1) +
            Bytes.u16Le(0) +
            Bytes.u32Le(sessionId) +
            Bytes.u32Le(size) +
            byteArrayOf(0x00, 0x00) // scene + attrs for v7+
        val list = Sessions.parseSessionList(payload, protocolVersion = 20)
        assertEquals(1, list.total)
        assertEquals(1, list.entries.size)
        assertEquals(sessionId, list.entries[0].sessionId)
        assertEquals(size, list.entries[0].sizeBytes)
    }

    @Test
    fun encodeGetSessions_hasCmd26() {
        val frame = Sessions.encodeGetSessions(nowUnix = 0x6adb9cb1L)
        assertEquals(0x01, frame[0].toInt() and 0xFF)
        assertEquals(Commands.GET_SESSIONS, Bytes.readU16Le(frame, 1))
        assertEquals(0x6adb9cb1L, Bytes.readU32Le(frame, 3))
    }

    @Test
    fun parseSyncStop_crcFfffFixture() {
        // Tail: 011d00 f49ddb6a ffff
        val raw = Bytes.hexToBytes("011d00f49ddb6affff")
        val packet = Framing.decodeInbound(raw)
        assertIs<InboundPacket.CommandResponse>(packet)
        assertEquals(Commands.SYNC_FILE_STOP, packet.cmd)
        val stop = Sessions.parseSyncStopResponse(packet.payload)
        assertEquals(0x6adb9df4L, stop.sessionId)
        assertEquals(0xFFFF, stop.crc)
    }

    @Test
    fun parseRecordingData_endMarker() {
        // type 2, no session id (we'll pass protocol < 7), offset FFFFFFFF, code 1
        val raw = byteArrayOf(
            0x02,
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
            0x01,
        )
        val packet = Sessions.parseRecordingData(raw, protocolVersion = 1)
        assertIs<RecordingDataPacket.End>(packet)
        assertEquals(1, packet.code)
    }

    @Test
    fun parseRecordingData_chunkWithSessionId() {
        val audio = byteArrayOf(0xB8.toByte(), 0x01, 0x02)
        val raw = byteArrayOf(0x02) +
            Bytes.u32Le(0x6adb9df4L) +
            Bytes.u32Le(0) +
            byteArrayOf(audio.size.toByte()) +
            audio
        val packet = Sessions.parseRecordingData(raw, protocolVersion = 20)
        assertIs<RecordingDataPacket.Chunk>(packet)
        assertEquals(0x6adb9df4L, packet.sessionId)
        assertEquals(0L, packet.offset)
        assertTrue(packet.data.contentEquals(audio))
    }

    @Test
    fun entrySize_byProtocol() {
        assertEquals(8, Sessions.entrySize(1))
        assertEquals(9, Sessions.entrySize(5))
        assertEquals(10, Sessions.entrySize(20))
    }
}
