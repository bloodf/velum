package dev.velum.protocol

/**
 * Recording session list (cmd 26) and download (cmd 28/29 + type-2 data).
 */
data class SessionEntry(
    /** Recording start time in Unix seconds; also the session id. */
    val sessionId: Long,
    val sizeBytes: Long,
    val scene: Int? = null,
    val attributes: Int? = null,
)

data class SessionListResponse(
    /** Echo of the `now` timestamp the app sent. */
    val echoedNow: Long,
    val total: Int,
    val firstIndex: Int,
    val entries: List<SessionEntry>,
)

object Sessions {
    fun encodeGetSessions(nowUnix: Long, startSession: Long = 0, flag: Int = 0): ByteArray {
        val params = Bytes.u32Le(nowUnix) + Bytes.u32Le(startSession) + byteArrayOf(flag.toByte())
        return Framing.encodeCommand(Commands.GET_SESSIONS, params)
    }

    /**
     * Entry size depends on protocol version:
     * - v1: 8 bytes (id + size)
     * - v2–6: 9 bytes (+ scene)
     * - v7+: 10 bytes (+ 2 attribute bytes)
     */
    fun entrySize(protocolVersion: Int): Int = when {
        protocolVersion <= 1 -> 8
        protocolVersion in 2..6 -> 9
        else -> 10
    }

    /**
     * Parse cmd-26 payload (bytes after `[0x01][cmd u16]`).
     *
     * Layout: `echoedNow u32 | total u16 | firstIndex u16 | entries…`
     * (Community note: the device echoes the request timestamp in bytes 0–3 of
     * the payload, so totals sit at bytes 4–5.)
     */
    fun parseSessionList(payload: ByteArray, protocolVersion: Int): SessionListResponse {
        require(payload.size >= 8) { "Session list payload too short" }
        val echoedNow = Bytes.readU32Le(payload, 0)
        val total = Bytes.readU16Le(payload, 4)
        val firstIndex = Bytes.readU16Le(payload, 6)
        val size = entrySize(protocolVersion)
        val entries = mutableListOf<SessionEntry>()
        var offset = 8
        while (offset + size <= payload.size && entries.size < total) {
            val id = Bytes.readU32Le(payload, offset)
            val fileSize = Bytes.readU32Le(payload, offset + 4)
            val scene = if (size >= 9) payload[offset + 8].toInt() and 0xFF else null
            val attrs = if (size >= 10) payload[offset + 9].toInt() and 0xFF else null
            entries += SessionEntry(id, fileSize, scene, attrs)
            offset += size
        }
        return SessionListResponse(echoedNow, total, firstIndex, entries)
    }

    fun encodeSyncStart(sessionId: Long, startOffset: Long, endOffset: Long): ByteArray {
        val params = Bytes.u32Le(sessionId) + Bytes.u32Le(startOffset) + Bytes.u32Le(endOffset)
        return Framing.encodeCommand(Commands.SYNC_FILE_START, params)
    }

    fun encodeSyncStop(): ByteArray = Framing.encodeCommand(Commands.SYNC_FILE_STOP)

    fun parseSyncStartResponse(payload: ByteArray): SyncStartResponse {
        require(payload.size >= 5) { "Sync start response too short" }
        return SyncStartResponse(
            sessionId = Bytes.readU32Le(payload, 0),
            status = payload[4].toInt() and 0xFF,
        )
    }

    fun parseSyncStopResponse(payload: ByteArray): SyncStopResponse {
        require(payload.size >= 6) { "Sync stop response too short" }
        return SyncStopResponse(
            sessionId = Bytes.readU32Le(payload, 0),
            crc = Bytes.readU16Le(payload, 4),
        )
    }

    /**
     * Type-2 recording data packet.
     * `[0x02][session_id u32 if protocol≥7][offset u32][len u8][data…]`
     * Offset `0xFFFFFFFF` means end-of-stream; a code byte follows.
     */
    fun parseRecordingData(raw: ByteArray, protocolVersion: Int): RecordingDataPacket {
        require(raw.isNotEmpty() && (raw[0].toInt() and 0xFF) == PacketType.RecordingData.code) {
            "Not a recording data packet"
        }
        var offset = 1
        val sessionId = if (protocolVersion >= 7) {
            require(raw.size >= offset + 4)
            Bytes.readU32Le(raw, offset).also { offset += 4 }
        } else {
            null
        }
        require(raw.size >= offset + 4) { "Missing offset field" }
        val dataOffset = Bytes.readU32Le(raw, offset)
        offset += 4
        if (dataOffset == 0xFFFF_FFFFL) {
            val code = if (offset < raw.size) raw[offset].toInt() and 0xFF else 0
            return RecordingDataPacket.End(sessionId, code)
        }
        require(offset < raw.size) { "Missing length" }
        val len = raw[offset].toInt() and 0xFF
        offset += 1
        require(raw.size >= offset + len) { "Truncated audio data" }
        val audio = raw.copyOfRange(offset, offset + len)
        return RecordingDataPacket.Chunk(sessionId, dataOffset, audio)
    }
}

data class SyncStartResponse(val sessionId: Long, val status: Int) {
    val refused: Boolean get() = status > 0
}

data class SyncStopResponse(val sessionId: Long, val crc: Int)

sealed class RecordingDataPacket {
    data class Chunk(
        val sessionId: Long?,
        val offset: Long,
        val data: ByteArray,
    ) : RecordingDataPacket() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Chunk) return false
            return sessionId == other.sessionId &&
                offset == other.offset &&
                data.contentEquals(other.data)
        }

        override fun hashCode(): Int =
            31 * (31 * (sessionId?.hashCode() ?: 0) + offset.hashCode()) + data.contentHashCode()
    }

    data class End(val sessionId: Long?, val code: Int) : RecordingDataPacket()
}
