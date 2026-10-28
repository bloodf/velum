package dev.velum.ble.fake

import dev.velum.protocol.BatteryReading
import dev.velum.protocol.Bytes
import dev.velum.protocol.Commands
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
import kotlinx.coroutines.CompletableDeferred
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
 * Test knobs: [muteResponses] (unlock/command timeouts), [batteryPayloadOverride]
 * (malformed battery frames), [emitUnsolicitedBattery] (notify path).
 */
class FakePlaudPeripheral(
    private val cipher: SessionCipher,
    private val clientKeys: DeviceIdentityKeys,
    private val sessionFile: ByteArray,
    private val sessionId: Long = 1_700_000_000L,
    var batteryPercent: Int = 0x5d,
    private val requirePublicKey: Boolean = true,
    private val random: Random = Random.Default,
) {
    val notifications = MutableSharedFlow<ByteArray>(extraBufferCapacity = 128)

    private val mutex = Mutex()
    private val snChunks = mutableListOf<UnlockChunk>()
    private val pubChunks = mutableListOf<UnlockChunk>()

    private var secret: Protocol20SessionSecret? = null
    private var appRecvSeq: Long = 2
    private var deviceSendSeq: Long = 0
    private var unlocked = false
    private var handshakeFailed = false

    /** When true, accept writes but emit no responses (client-side timeout tests). */
    var muteResponses: Boolean = false

    /**
     * When set, the next battery cmd-9 response uses this payload instead of
     * the normal `00 XX` reading (malformed-frame tests). Cleared after use.
     */
    var batteryPayloadOverride: ByteArray? = null

    val isUnlocked: Boolean get() = unlocked

    /**
     * Completes when a valid FE10 snSignature has been accepted (before optional
     * FE11 / FE12). Used by mid-handshake disconnect tests.
     */
    private var fe10Received = CompletableDeferred<Unit>()

    suspend fun awaitFe10Received() {
        fe10Received.await()
    }

    /** Clear session state so the next connection requires FE10 again. */
    fun resetUnlock() {
        snChunks.clear()
        pubChunks.clear()
        secret = null
        appRecvSeq = 2
        deviceSendSeq = 0
        unlocked = false
        handshakeFailed = false
        if (fe10Received.isCompleted) {
            fe10Received = CompletableDeferred()
        }
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

    /** Push an unsolicited encrypted cmd-9 battery notification (device → app). */
    suspend fun emitUnsolicitedBattery(percent: Int = batteryPercent) = mutex.withLock {
        check(unlocked) { "Cannot emit battery before unlock" }
        batteryPercent = percent
        val batPayload = byteArrayOf(0x00, percent.toByte())
        emitCommand(Commands.BATTERY_LIKELY, batPayload)
    }

    /** Push an unsolicited encrypted command-response (interleaved-waiter tests). */
    suspend fun emitUnsolicitedCommandResponse(cmd: Int, payload: ByteArray) = mutex.withLock {
        check(unlocked) { "Cannot emit command response before unlock" }
        emitCommand(cmd, payload)
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
                        // Do not include signature bytes in the error text.
                        error("HANDSHAKE_FAIL: FE10 snSignature empty or does not match imported keys")
                    }
                    if (!fe10Received.isCompleted) {
                        fe10Received.complete(Unit)
                    }
                    if (muteResponses) return
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
                    if (muteResponses) return
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
                Bytes.u32Le(sessionFile.size.toLong()).copyInto(entry, 4)
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
                emitCommand(
                    Commands.SYNC_FILE_START,
                    Bytes.u32Le(sid) + byteArrayOf(0),
                )
                var offset = 0
                val maxChunk = 100
                while (offset < sessionFile.size) {
                    val end = minOf(offset + maxChunk, sessionFile.size)
                    val slice = sessionFile.copyOfRange(offset, end)
                    val frame = byteArrayOf(PacketType.RecordingData.code.toByte()) +
                        Bytes.u32Le(sessionId) +
                        Bytes.u32Le(offset.toLong()) +
                        byteArrayOf(slice.size.toByte()) +
                        slice
                    emitEncrypted(frame)
                    offset = end
                }
                val endFrame = byteArrayOf(PacketType.RecordingData.code.toByte()) +
                    Bytes.u32Le(sessionId) +
                    Bytes.u32Le(0xFFFF_FFFFL) +
                    byteArrayOf(0)
                emitEncrypted(endFrame)
            }
            Commands.SYNC_FILE_STOP -> {
                emitCommand(
                    Commands.SYNC_FILE_STOP,
                    Bytes.u32Le(sessionId) + Bytes.u16Le(0xFFFF),
                )
            }
            Commands.BATTERY_LIKELY -> {
                val override = batteryPayloadOverride
                batteryPayloadOverride = null
                val batPayload = override ?: byteArrayOf(0x00, batteryPercent.toByte())
                emitCommand(Commands.BATTERY_LIKELY, batPayload)
                if (override == null) {
                    check(BatteryReading.parse(batPayload).percent == batteryPercent)
                }
            }
            else -> error("Fake peripheral does not handle cmd $cmd")
        }
    }

    private suspend fun emitCommand(cmd: Int, payload: ByteArray) {
        if (muteResponses) return
        val frame = ByteArray(1 + 2 + payload.size)
        frame[0] = PacketType.CommandResponse.code.toByte()
        Bytes.u16Le(cmd).copyInto(frame, 1)
        payload.copyInto(frame, 3)
        emitEncrypted(frame)
    }

    private suspend fun emitEncrypted(frame: ByteArray) {
        if (muteResponses) return
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
        if (muteResponses) return
        notifications.emit(frame)
    }
}
