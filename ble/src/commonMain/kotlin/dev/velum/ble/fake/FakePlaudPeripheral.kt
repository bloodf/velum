package dev.velum.ble.fake

import dev.velum.ble.client.ByteArraySessionContent
import dev.velum.ble.client.SessionContent
import dev.velum.protocol.BatteryReading
import dev.velum.protocol.Bytes
import dev.velum.protocol.Commands
import dev.velum.protocol.Crc16Ccitt
import dev.velum.protocol.DeviceIdentityKeys
import dev.velum.protocol.Framing
import dev.velum.protocol.PacketType
import dev.velum.protocol.Protocol20SessionSecret
import dev.velum.protocol.SessionCipher
import dev.velum.protocol.Sessions
import dev.velum.protocol.Unlock
import dev.velum.protocol.UnlockChunk
import dev.velum.protocol.UnlockSecretLayout
import kotlin.random.Random
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-process Note Pro (project 881, protocol 20) that speaks the same framing
 * and [SessionCipher] as [dev.velum.ble.client.PlaudDeviceClient].
 *
 * FE10 snSignature must be non-empty and match [clientKeys] on every connection.
 * Call [resetUnlock] on disconnect so the next connect re-runs unlock.
 *
 * When [requirePublicKey] is false (E2EE off), FE11 is skipped and the session
 * secret is emitted after a valid FE10.
 *
 * Honors sync-start offsets for resume tests and returns CRC-16/CCITT of the full
 * session content on sync-stop. [fault] injects disconnects / corruption / etc.
 */
class FakePlaudPeripheral(
    private val cipher: SessionCipher,
    private val clientKeys: DeviceIdentityKeys,
    private val sessionContent: SessionContent,
    private val sessionId: Long = 1_700_000_000L,
    private val batteryPercent: Int = 0x5d,
    private val requirePublicKey: Boolean = true,
    private val random: Random = Random.Default,
    private val maxChunk: Int = 100,
) {
    constructor(
        cipher: SessionCipher,
        clientKeys: DeviceIdentityKeys,
        sessionFile: ByteArray,
        sessionId: Long = 1_700_000_000L,
        batteryPercent: Int = 0x5d,
        requirePublicKey: Boolean = true,
        random: Random = Random.Default,
        maxChunk: Int = 100,
    ) : this(
        cipher = cipher,
        clientKeys = clientKeys,
        sessionContent = ByteArraySessionContent(sessionFile),
        sessionId = sessionId,
        batteryPercent = batteryPercent,
        requirePublicKey = requirePublicKey,
        random = random,
        maxChunk = maxChunk,
    )

    val notifications = MutableSharedFlow<ByteArray>(extraBufferCapacity = 256)

    /** Mutable so tests can clear a fault and resume. */
    var fault: DownloadFault = DownloadFault.None

    /**
     * When true, sync-stop reports a wrong CRC (forces client-side mismatch without
     * corrupting the byte stream — useful for Wi-Fi→BLE fallback CRC checks).
     */
    var forceWrongSyncStopCrc: Boolean = false

    private val mutex = Mutex()
    private val snChunks = mutableListOf<UnlockChunk>()
    private val pubChunks = mutableListOf<UnlockChunk>()

    private var secret: Protocol20SessionSecret? = null
    private var appRecvSeq: Long = 2
    private var deviceSendSeq: Long = 0
    private var unlocked = false
    private var handshakeFailed = false

    fun sessionSize(): Long = sessionContent.size()

    fun fullContentCrc(): Int = crcOfRange(0L, sessionContent.size())

    /** Clear session state so the next connection requires FE10 again. */
    fun resetUnlock() {
        snChunks.clear()
        pubChunks.clear()
        secret = null
        appRecvSeq = 2
        deviceSendSeq = 0
        unlocked = false
        handshakeFailed = false
    }

    suspend fun onWrite(payload: ByteArray) = mutex.withLock {
        if (handshakeFailed) {
            error("Handshake already failed — reconnect and send FE10 snSignature")
        }
        if (!unlocked) {
            handleUnlock(payload)
            return
        }
        val sec = secret ?: error("Missing session secret")
        val opened = cipher.openPacket(sec, appRecvSeq, payload)
            ?: error("Failed to open app packet at seq=$appRecvSeq")
        appRecvSeq = opened.first + 1
        handleCommand(opened.second)
    }

    private suspend fun handleUnlock(payload: ByteArray) {
        require(payload.size >= 2)
        val opcode = Bytes.readU16Le(payload, 0)
        when (opcode) {
            Commands.UNLOCK_SN_SIGNATURE -> {
                snChunks += Unlock.parseChunk(payload)
                if (snChunks.size >= snChunks.first().total) {
                    val signature = Unlock.reassemble(snChunks)
                    if (signature.isEmpty() || !signature.contentEquals(clientKeys.serialSignature)) {
                        handshakeFailed = true
                        snChunks.clear()
                        error("HANDSHAKE_FAIL: FE10 snSignature empty or does not match imported keys")
                    }
                    if (requirePublicKey) {
                        emitRaw(
                            Bytes.u16Le(Commands.UNLOCK_NEED_PUBLIC_KEY) +
                                byteArrayOf(1, 0),
                        )
                    } else {
                        emitSessionSecret()
                    }
                }
            }
            Commands.UNLOCK_SESSION_SECRET -> {
                if (!requirePublicKey) {
                    error("Public key FE12 unexpected when requirePublicKey=false")
                }
                pubChunks += Unlock.parseChunk(payload)
                if (pubChunks.size >= pubChunks.first().total) {
                    Unlock.reassemble(pubChunks)
                    emitSessionSecret()
                }
            }
            else -> error("Unexpected unlock opcode 0x${opcode.toString(16)}")
        }
    }

    private suspend fun emitSessionSecret() {
        val key = ByteArray(32) { random.nextInt(0, 256).toByte() }
        val nonce = ByteArray(12) { random.nextInt(0, 256).toByte() }
        val aad = ByteArray(12) { random.nextInt(0, 256).toByte() }
        val testCt = cipher.chacha20Poly1305Seal(
            key,
            nonce,
            aad,
            UnlockSecretLayout.TEST_PLAINTEXT,
        )
        val blob = key + nonce + aad + testCt
        val sealed = cipher.rsaEncryptPkcs1(clientKeys.publicKeyPem, blob)
        secret = Protocol20SessionSecret(key, nonce, aad)
        unlocked = true
        appRecvSeq = 2
        deviceSendSeq = 0
        for (chunk in Unlock.encodeChunks(Commands.UNLOCK_SESSION_SECRET, sealed)) {
            emitRaw(chunk)
        }
    }

    private suspend fun handleCommand(packet: ByteArray) {
        require(packet.isNotEmpty() && packet[0] == Framing.APP_PREFIX) {
            "Expected app prefix, got ${packet.firstOrNull()}"
        }
        require(packet.size >= 2) { "App command too short" }
        val cmd = if (packet.size >= 3) {
            Bytes.readU16Le(packet, 1)
        } else {
            packet[1].toInt() and 0xFF
        }
        val payload = if (packet.size > 3) packet.copyOfRange(3, packet.size) else ByteArray(0)
        when (cmd) {
            Commands.HANDSHAKE -> {
                val hsPayload = Bytes.hexToBytes("001400051e0101000156000701")
                emitCommand(Commands.HANDSHAKE, hsPayload)
            }
            Commands.GET_SESSIONS -> {
                val echoed = if (payload.size >= 4) Bytes.readU32Le(payload, 0) else 0L
                val entrySize = Sessions.entrySize(20)
                val entry = ByteArray(entrySize)
                Bytes.u32Le(sessionId).copyInto(entry, 0)
                Bytes.u32Le(sessionContent.size()).copyInto(entry, 4)
                if (entrySize >= 9) entry[8] = 0
                if (entrySize >= 10) entry[9] = 0
                val listPayload = Bytes.u32Le(echoed) +
                    Bytes.u16Le(1) +
                    Bytes.u16Le(0) +
                    entry
                emitCommand(Commands.GET_SESSIONS, listPayload)
            }
            Commands.SYNC_FILE_START -> {
                val sid = Bytes.readU32Le(payload, 0)
                val startOffset = if (payload.size >= 8) Bytes.readU32Le(payload, 4) else 0L
                emitCommand(
                    Commands.SYNC_FILE_START,
                    Bytes.u32Le(sid) + byteArrayOf(0),
                )
                emitSessionData(startOffset)
            }
            Commands.SYNC_FILE_STOP -> {
                val crc = when {
                    forceWrongSyncStopCrc -> fullContentCrc() xor 0xFFFF
                    else -> fullContentCrc()
                }
                emitCommand(
                    Commands.SYNC_FILE_STOP,
                    Bytes.u32Le(sessionId) + Bytes.u16Le(crc),
                )
            }
            Commands.BATTERY_LIKELY -> {
                val batPayload = byteArrayOf(0x00, batteryPercent.toByte())
                emitCommand(Commands.BATTERY_LIKELY, batPayload)
                check(BatteryReading.parse(batPayload).percent == batteryPercent)
            }
            else -> error("Fake peripheral does not handle cmd $cmd")
        }
    }

    private suspend fun emitSessionData(startOffset: Long) {
        val total = sessionContent.size()
        require(startOffset in 0..total) { "startOffset=$startOffset out of range size=$total" }

        val truncateCap = when (val f = fault) {
            is DownloadFault.TruncateFinalChunk -> f.sendOnlyBytes.coerceIn(0L, total)
            else -> total
        }
        val dropAt = when (val f = fault) {
            is DownloadFault.DropAtOffset -> f.offset
            else -> null
        }
        val corruptAt = when (val f = fault) {
            is DownloadFault.CorruptAtOffset -> f.offset
            else -> null
        }
        val duplicateAt = when (val f = fault) {
            is DownloadFault.DuplicateChunkAt -> f.offset
            else -> null
        }

        var offset = startOffset
        var duplicated = false
        var dropped = false
        while (offset < truncateCap) {
            if (dropAt != null && offset >= dropAt) {
                // Stop mid-stream without End — client times out; chunks already
                // buffered are drained and verified so resume can continue.
                dropped = true
                break
            }
            val end = minOf(offset + maxChunk, truncateCap)
            val length = (end - offset).toInt()
            val slice = sessionContent.slice(offset, length)
            if (corruptAt != null && offset <= corruptAt && corruptAt < end) {
                val idx = (corruptAt - offset).toInt()
                slice[idx] = (slice[idx].toInt() xor 0xFF).toByte()
            }
            emitDataChunk(offset, slice)
            if (duplicateAt != null && !duplicated && offset == duplicateAt) {
                emitDataChunk(offset, slice.copyOf())
                duplicated = true
            }
            offset = end
        }

        if (dropped) return

        val endFrame = byteArrayOf(PacketType.RecordingData.code.toByte()) +
            Bytes.u32Le(sessionId) +
            Bytes.u32Le(0xFFFF_FFFFL) +
            byteArrayOf(0)
        emitEncrypted(endFrame)
    }

    private suspend fun emitDataChunk(offset: Long, slice: ByteArray) {
        val frame = byteArrayOf(PacketType.RecordingData.code.toByte()) +
            Bytes.u32Le(sessionId) +
            Bytes.u32Le(offset) +
            byteArrayOf(slice.size.toByte()) +
            slice
        emitEncrypted(frame)
    }

    private fun crcOfRange(start: Long, end: Long): Int {
        var crc = Crc16Ccitt.INITIAL
        var offset = start
        val buf = ByteArray(4096)
        while (offset < end) {
            val n = minOf(buf.size.toLong(), end - offset).toInt()
            sessionContent.copyInto(offset, n, buf, 0)
            // update only the used prefix
            crc = Crc16Ccitt.update(crc, if (n == buf.size) buf else buf.copyOf(n))
            offset += n
        }
        return crc
    }

    private suspend fun emitCommand(cmd: Int, payload: ByteArray) {
        val frame = ByteArray(1 + 2 + payload.size)
        frame[0] = PacketType.CommandResponse.code.toByte()
        Bytes.u16Le(cmd).copyInto(frame, 1)
        payload.copyInto(frame, 3)
        emitEncrypted(frame)
    }

    private suspend fun emitEncrypted(frame: ByteArray) {
        val sec = secret
        if (sec == null) {
            emitRaw(frame)
        } else {
            val sealed = cipher.sealPacket(sec, deviceSendSeq, frame)
            deviceSendSeq += 1
            emitRaw(sealed)
        }
    }

    private suspend fun emitRaw(frame: ByteArray) {
        notifications.emit(frame)
    }
}
