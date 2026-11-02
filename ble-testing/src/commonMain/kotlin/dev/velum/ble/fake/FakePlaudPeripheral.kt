package dev.velum.ble.fake

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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield

/**
 * In-process Note Pro (project 881, protocol 20) that speaks the same framing
 * and [SessionCipher] as [dev.velum.ble.client.PlaudDeviceClient].
 *
 * FE10 snSignature must be non-empty and match [clientKeys] on every connection.
 * Call [resetUnlock] on disconnect so the next connect re-runs unlock.
 *
 * When [requirePublicKey] is false (E2EE off), FE11 is skipped and the session
 * secret is emitted after a valid FE10.
 */
class FakePlaudPeripheral(
    private val cipher: SessionCipher,
    private val clientKeys: DeviceIdentityKeys,
    private val sessionFile: ByteArray,
    private val sessionId: Long = 1_700_000_000L,
    private val batteryPercent: Int = 0x5d,
    private val requirePublicKey: Boolean = true,
    private val random: Random = Random.Default,
    /** Audio bytes per type-2 packet (the wire length field is u8, so at most 255). */
    private val chunkSize: Int = 100,
    private val faults: TransferFaults = TransferFaults(),
    /** CRC reported in the SYNC_FILE_STOP response. */
    private val stopCrc: StopCrc = StopCrc.NotProvided,
    private val stopReply: StopReply = StopReply.Cmd29,
) {
    init {
        require(chunkSize in 1..255) { "chunkSize must fit the u8 length field" }
    }

    /** What the device reports as the file CRC when a sync stops. */
    enum class StopCrc {
        /** `0xFFFF`, as in the only real-device capture (docs/protocol/README.md). */
        NotProvided,

        /** CRC-16/CCITT-FALSE of the whole session file (assumed algorithm, unconfirmed on hardware). */
        Ccitt,

        /** A value that never matches the file (a device whose CRC differs from ours). */
        Wrong,
    }

    /** How the device answers SYNC_FILE_STOP. */
    enum class StopReply {
        Cmd29,

        /** Answers as command 30 (never sent by the app; delete in our docs). */
        Cmd30,

        /** Never answers. */
        Silent,
    }

    val notifications = MutableSharedFlow<ByteArray>(extraBufferCapacity = 128)

    /** Every command received after unlock, in order. */
    val receivedCommands: List<Int> get() = _receivedCommands.toList()
    private val _receivedCommands = mutableListOf<Int>()

    /** `startOffset` of every SYNC_FILE_START received, in order. */
    val syncStartOffsets: List<Long> get() = _syncStartOffsets.toList()
    private val _syncStartOffsets = mutableListOf<Long>()

    private val mutex = Mutex()
    private val emitMutex = Mutex()
    private var streamJob: Job? = null
    private var lastSealed: ByteArray? = null
    private var staleListReply: ByteArray? = null
    private val snChunks = mutableListOf<UnlockChunk>()
    private val pubChunks = mutableListOf<UnlockChunk>()

    private var secret: Protocol20SessionSecret? = null
    private var appRecvSeq: Long = 2
    private var deviceSendSeq: Long = 0
    private var unlocked = false
    private var handshakeFailed = false

    /** Clear session state so the next connection requires FE10 again. */
    fun resetUnlock() {
        streamJob?.cancel()
        streamJob = null
        snChunks.clear()
        pubChunks.clear()
        secret = null
        appRecvSeq = 2
        deviceSendSeq = 0
        unlocked = false
        handshakeFailed = false
    }

    suspend fun onWrite(payload: ByteArray): Unit = mutex.withLock {
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
        // Framing.encodeCommand trims trailing zero bytes, so pad back what a field read needs.
        val cmd = Bytes.readU16Le(packet.copyOf(maxOf(packet.size, 3)), 1)
        val payload = packet.copyOf(maxOf(packet.size, 3 + MAX_COMMAND_PARAM_BYTES)).copyOfRange(3, 3 + MAX_COMMAND_PARAM_BYTES)
        _receivedCommands += cmd
        when (cmd) {
            Commands.HANDSHAKE -> {
                val hsPayload = Bytes.hexToBytes("001400051e0101000156000701")
                emitCommand(Commands.HANDSHAKE, hsPayload)
            }
            Commands.GET_SESSIONS -> {
                val echoed = Bytes.readU32Le(payload, 0)
                val entrySize = Sessions.entrySize(20)
                val entry = ByteArray(entrySize)
                Bytes.u32Le(sessionId).copyInto(entry, 0)
                Bytes.u32Le(sessionFile.size.toLong()).copyInto(entry, 4)
                if (entrySize >= 9) entry[8] = 0
                if (entrySize >= 10) entry[9] = 0
                val listPayload = Bytes.u32Le(echoed) +
                    Bytes.u16Le(1) +
                    Bytes.u16Le(0) +
                    entry
                if (faults.replayStaleListReply) staleListReply?.let { emitRaw(it) }
                emitCommand(Commands.GET_SESSIONS, listPayload)
                staleListReply = lastSealed
            }
            Commands.SYNC_FILE_START -> {
                val sid = Bytes.readU32Le(payload, 0)
                val startOffset = Bytes.readU32Le(payload, 4)
                _syncStartOffsets += startOffset
                val refuse = faults.refuseNonZeroStart && startOffset > 0
                emitCommand(
                    Commands.SYNC_FILE_START,
                    Bytes.u32Le(sid) + byteArrayOf(if (refuse) 1 else 0),
                )
                if (refuse) return
                // Like real GATT, the write completes first and notifications follow under backpressure.
                // Emitting inside the write would deadlock once the buffers fill (nothing reads yet).
                streamJob?.cancel()
                streamJob = launchDetached { streamSessionFrom(if (faults.ignoreStartOffset) 0 else startOffset.toInt()) }
            }
            Commands.SYNC_FILE_STOP -> {
                // A real device stops streaming; its reply follows the completed write.
                streamJob?.cancelAndJoin()
                val crc = when (stopCrc) {
                    StopCrc.NotProvided -> 0xFFFF
                    StopCrc.Ccitt -> Crc16Ccitt.compute(sessionFile)
                    StopCrc.Wrong -> Crc16Ccitt.compute(sessionFile) xor 0x0101
                }
                val replyCmd = when (stopReply) {
                    StopReply.Cmd29 -> Commands.SYNC_FILE_STOP
                    StopReply.Cmd30 -> Commands.DELETE_FILE
                    StopReply.Silent -> null
                }
                if (replyCmd != null) {
                    streamJob = launchDetached {
                        emitCommand(replyCmd, Bytes.u32Le(sessionId) + Bytes.u16Le(crc))
                    }
                }
            }
            Commands.BATTERY_LIKELY -> {
                val batPayload = byteArrayOf(0x00, batteryPercent.toByte())
                emitCommand(Commands.BATTERY_LIKELY, batPayload)
                check(BatteryReading.parse(batPayload).percent == batteryPercent)
            }
            else -> error("Fake peripheral does not handle cmd $cmd")
        }
    }

    /** Not a child of the write: like real GATT, a write completes (and any timeout around it ends) while this runs. */
    private suspend fun launchDetached(block: suspend () -> Unit): Job =
        CoroutineScope(currentCoroutineContext().minusKey(Job)).launch { block() }

    /** Emits type-2 chunks from [startOffset] to the end, applying one-shot [faults]. */
    private suspend fun streamSessionFrom(startOffset: Int) {
        var offset = startOffset
        faults.startPass(startOffset)
        while (offset < sessionFile.size) {
            val end = minOf(offset + chunkSize, sessionFile.size)
            val at = offset.toLong()
            // Link lost: nothing more arrives, not even the end marker.
            if (faults.takeDisconnect(at) || faults.takePassDisconnect(at)) return
            // At the offset the client expects next, so only the session id tells it apart.
            if (faults.stray.remove(at)) emitEncrypted(chunkFrame(sessionId + 1, at, ByteArray(10) { 0x77 }))
            if (faults.strayEnd.remove(at)) emitEncrypted(endFrame(sessionId + 1))
            faults.repeatBurst.remove(at)?.let { times ->
                val previous = chunkFrame(sessionId, at - chunkSize, sessionFile.copyOfRange(offset - chunkSize, offset))
                repeat(times) {
                    emitEncrypted(previous)
                    yield()
                }
            }
            if (faults.endlessRepeatAt == at) {
                val previous = chunkFrame(sessionId, at - chunkSize, sessionFile.copyOfRange(offset - chunkSize, offset))
                while (true) {
                    emitEncrypted(previous)
                    yield() // emit may not suspend, and this must stay cancellable
                }
            }
            if (faults.overlap.remove(at)) {
                emitEncrypted(chunkFrame(sessionId, at - 50, sessionFile.copyOfRange(offset - 50, offset + 50)))
                offset = end
                continue
            }
            val slice = sessionFile.copyOfRange(offset, end)
            if (faults.corrupt.remove(at)) slice[0] = (slice[0].toInt() xor 0x5A).toByte()
            val frame = chunkFrame(sessionId, if (faults.relativeOffsets) at - startOffset else at, slice)
            when {
                faults.drop.remove(at) -> Unit
                faults.garble.remove(at) -> emitEncrypted(frame, garble = true)
                faults.lostNotification.remove(at) -> emitEncrypted(frame, lost = true)
                faults.replay.remove(at) -> emitEncrypted(frame, replay = true)
                faults.duplicate.remove(at) -> { emitEncrypted(frame); emitEncrypted(frame) }
                else -> emitEncrypted(frame)
            }
            offset = end
        }
        emitEncrypted(endFrame(sessionId))
    }

    private fun endFrame(sid: Long) =
        byteArrayOf(PacketType.RecordingData.code.toByte()) +
            Bytes.u32Le(sid) +
            Bytes.u32Le(0xFFFF_FFFFL) +
            byteArrayOf(0)

    private fun chunkFrame(sid: Long, offset: Long, data: ByteArray) =
        byteArrayOf(PacketType.RecordingData.code.toByte()) +
            Bytes.u32Le(sid) +
            Bytes.u32Le(offset) +
            byteArrayOf(data.size.toByte()) +
            data

    private suspend fun emitCommand(cmd: Int, payload: ByteArray) {
        val frame = ByteArray(1 + 2 + payload.size)
        frame[0] = PacketType.CommandResponse.code.toByte()
        Bytes.u16Le(cmd).copyInto(frame, 1)
        payload.copyInto(frame, 3)
        emitEncrypted(frame)
    }

    private suspend fun emitEncrypted(
        frame: ByteArray,
        garble: Boolean = false,
        lost: Boolean = false,
        replay: Boolean = false,
    ) = emitMutex.withLock {
        val sec = secret
        if (sec == null) {
            if (!lost) emitRaw(frame)
        } else {
            val sealed = cipher.sealPacket(sec, deviceSendSeq, frame)
            deviceSendSeq += 1
            lastSealed = sealed
            // Flip a tag byte: the packet no longer authenticates (radio/link corruption).
            if (garble) sealed[sealed.size - 1] = (sealed[sealed.size - 1].toInt() xor 0x01).toByte()
            if (!lost) emitRaw(sealed)
            if (replay) emitRaw(sealed)
        }
    }

    private suspend fun emitRaw(frame: ByteArray) {
        notifications.emit(frame)
    }

    private companion object {
        /** Longest params among the commands this fake handles (SYNC_FILE_START: three u32). */
        const val MAX_COMMAND_PARAM_BYTES = 12
    }
}
