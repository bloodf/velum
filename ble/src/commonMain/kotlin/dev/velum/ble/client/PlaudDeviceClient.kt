package dev.velum.ble.client

import dev.velum.ble.BleTransport
import dev.velum.protocol.Battery
import dev.velum.protocol.BatteryReading
import dev.velum.protocol.Bytes
import dev.velum.protocol.Commands
import dev.velum.protocol.DeviceIdentityKeys
import dev.velum.protocol.ForbiddenOpcodes
import dev.velum.protocol.Framing
import dev.velum.protocol.HandshakeResponse
import dev.velum.protocol.HandshakeStatus
import dev.velum.protocol.InboundPacket
import dev.velum.protocol.Protocol20SessionSecret
import dev.velum.protocol.RecordingDataPacket
import dev.velum.protocol.SessionCipher
import dev.velum.protocol.SessionListResponse
import dev.velum.protocol.Sessions
import dev.velum.protocol.Unlock
import dev.velum.protocol.UnlockChunk
import dev.velum.protocol.UnlockSecretLayout
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * Protocol-20 client over [BleTransport].
 *
 * Never sends forbidden opcodes (5 / 104 / 128 / 0xFE20 / 143). Outbound frames
 * are checked before every write. For protocol ≥ 20, FE10 snSignature from the
 * imported keys is sent on every [unlock] / connect.
 */
class PlaudDeviceClient(
    private val ble: BleTransport,
    private val cipher: SessionCipher,
    private val keys: DeviceIdentityKeys,
    private val responseTimeout: Duration = 10.seconds,
    private val protocolVersionHint: Int = 20,
) {
    private val inbound = Channel<ByteArray>(capacity = 128)
    private val writeMutex = Mutex()
    private var notifyJob: Job? = null

    private var secret: Protocol20SessionSecret? = null
    private var sendSeq: Long = 2
    private var recvSeq: Long = 0
    private var protocolVersion: Int = 20

    val sessionSecret: Protocol20SessionSecret? get() = secret
    val linkProtocolVersion: Int get() = protocolVersion

    suspend fun start() {
        if (notifyJob != null) return
        val scope = CoroutineScope(coroutineContext)
        notifyJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            ble.notifications().collect { inbound.send(it) }
        }
    }

    fun close() {
        notifyJob?.cancel()
        notifyJob = null
        inbound.close()
    }

    /**
     * Unlock: FE10 SN signature → optional FE11 → FE12 RSA-wrapped session secret.
     * Starts the encrypted link with app send counter at 2.
     *
     * For protocol ≥ 20, always sends FE10 from the imported [DeviceIdentityKeys.serialSignature]
     * on every call (every connect). Missing / empty signature fails clearly.
     */
    suspend fun unlock() {
        start()
        writeMutex.withLock {
            if (protocolVersionHint >= 20) {
                require(keys.serialSignature.isNotEmpty()) {
                    "snSignature missing — import sn-sign once from Plaud storage; " +
                        "Velum cannot generate FE10 locally and never contacts api.plaud.ai"
                }
                require(keys.serialSignature.size == 256) {
                    "snSignature must be 256 bytes for protocol ≥ 20 FE10, " +
                        "was ${keys.serialSignature.size}"
                }
            }
            for (chunk in Unlock.encodeSnSignatureChunks(keys.serialSignature)) {
                assertOutboundAllowed(chunk)
                ble.write(chunk, withoutResponse = true)
            }

            val first = awaitRaw(responseTimeout) { raw ->
                raw.size >= 2 && Bytes.readU16Le(raw, 0) in
                    setOf(Commands.UNLOCK_NEED_PUBLIC_KEY, Commands.UNLOCK_SESSION_SECRET)
            }

            val secretChunks = mutableListOf<UnlockChunk>()
            when (Bytes.readU16Le(first, 0)) {
                Commands.UNLOCK_NEED_PUBLIC_KEY -> {
                    val pem = keys.publicKeyPem.encodeToByteArray()
                    for (chunk in Unlock.encodePublicKeyChunks(pem)) {
                        assertOutboundAllowed(chunk)
                        ble.write(chunk, withoutResponse = true)
                    }
                    collectUnlockChunks(Commands.UNLOCK_SESSION_SECRET, secretChunks)
                }
                Commands.UNLOCK_SESSION_SECRET -> {
                    secretChunks += Unlock.parseChunk(first)
                    while (secretChunks.size < secretChunks.first().total) {
                        val raw = awaitRaw(responseTimeout) {
                            it.size >= 2 &&
                                Bytes.readU16Le(it, 0) == Commands.UNLOCK_SESSION_SECRET
                        }
                        secretChunks += Unlock.parseChunk(raw)
                    }
                }
                else -> error("Unexpected unlock response")
            }

            val sealed = Unlock.reassemble(secretChunks)
            val decrypted = cipher.rsaDecryptPkcs1(keys.privateKeyPem, sealed)
            val parsed = UnlockSecretLayout.parseHeader(decrypted)
            val testCt = UnlockSecretLayout.testCiphertext(decrypted)
            val testPt = cipher.chacha20Poly1305Open(
                parsed.key,
                parsed.nonce,
                parsed.aad,
                testCt,
            ) ?: error("Unlock test vector failed auth")
            require(testPt.contentEquals(UnlockSecretLayout.TEST_PLAINTEXT)) {
                "Unlock test plaintext mismatch"
            }
            secret = parsed
            sendSeq = 2
            recvSeq = 0
        }
    }

    suspend fun handshake(): HandshakeResponse {
        val packet = Framing.encodeHandshake(keys.bindToken, protocolVersionHint = 20)
        val response = transactCommand(Commands.HANDSHAKE, packet)
        val hs = HandshakeResponse.parse(response)
        require(hs.status == HandshakeStatus.Ok) { "Handshake failed: ${hs.status}" }
        protocolVersion = hs.protocolVersion
        return hs
    }

    suspend fun listSessions(nowUnix: Long = 0L): SessionListResponse {
        val packet = Sessions.encodeGetSessions(nowUnix = nowUnix)
        val payload = transactCommand(Commands.GET_SESSIONS, packet)
        return Sessions.parseSessionList(payload, protocolVersion)
    }

    suspend fun downloadSession(sessionId: Long, sizeBytes: Long): ByteArray {
        val start = Sessions.encodeSyncStart(sessionId, 0, sizeBytes)
        val startPayload = transactCommand(Commands.SYNC_FILE_START, start)
        val startRsp = Sessions.parseSyncStartResponse(startPayload)
        require(!startRsp.refused) { "Sync start refused status=${startRsp.status}" }

        val out = ArrayList<Byte>()
        while (true) {
            val raw = awaitDecryptedFrame(responseTimeout) { true }
            when (val inboundPacket = Framing.decodeInbound(raw)) {
                is InboundPacket.RecordingData -> {
                    when (val data = Sessions.parseRecordingData(raw, protocolVersion)) {
                        is RecordingDataPacket.Chunk -> out.addAll(data.data.asList())
                        is RecordingDataPacket.End -> break
                    }
                }
                is InboundPacket.CommandResponse -> {
                    if (inboundPacket.cmd == Commands.SYNC_FILE_STOP) break
                }
                else -> Unit
            }
            if (sizeBytes > 0 && out.size.toLong() >= sizeBytes) break
        }

        val stopPayload = transactCommand(Commands.SYNC_FILE_STOP, Sessions.encodeSyncStop())
        Sessions.parseSyncStopResponse(stopPayload)
        return out.toByteArray()
    }

    suspend fun readBattery(): BatteryReading {
        val packet = Battery.encodeBatteryRequest()
        val payload = transactCommand(Commands.BATTERY_LIKELY, packet)
        return BatteryReading.parse(payload)
    }

    /**
     * Exposed for tests: attempt to send an arbitrary opcode (still guarded).
     */
    suspend fun writeCommandForTest(cmd: Int, params: ByteArray = ByteArray(0)) {
        ForbiddenOpcodes.requireAllowed(cmd)
        writeOutbound(Framing.encodeCommand(cmd, params))
    }

    private suspend fun collectUnlockChunks(
        opcode: Int,
        into: MutableList<UnlockChunk>,
    ) {
        while (true) {
            val raw = awaitRaw(responseTimeout) {
                it.size >= 2 && Bytes.readU16Le(it, 0) == opcode
            }
            val chunk = Unlock.parseChunk(raw)
            into += chunk
            if (into.size >= chunk.total) break
        }
    }

    private suspend fun transactCommand(expectedCmd: Int, packet: ByteArray): ByteArray {
        writeOutbound(packet)
        val raw = awaitDecryptedFrame(responseTimeout) { frame ->
            val decoded = runCatching { Framing.decodeInbound(frame) }.getOrNull()
            decoded is InboundPacket.CommandResponse && decoded.cmd == expectedCmd
        }
        val decoded = Framing.decodeInbound(raw) as InboundPacket.CommandResponse
        return decoded.payload
    }

    private suspend fun writeOutbound(packet: ByteArray) {
        writeMutex.withLock {
            assertOutboundAllowed(packet)
            val sec = secret
            if (sec == null) {
                ble.write(packet, withoutResponse = false)
            } else {
                val sealed = cipher.sealPacket(sec, sendSeq, packet)
                sendSeq += 1
                ble.write(sealed, withoutResponse = false)
            }
        }
    }

    private suspend fun awaitDecryptedFrame(
        timeout: Duration,
        predicate: (ByteArray) -> Boolean,
    ): ByteArray {
        val sec = secret
        return withTimeout(timeout) {
            while (true) {
                val raw = inbound.receive()
                val frame = if (sec != null) {
                    val opened = cipher.openPacket(sec, recvSeq, raw) ?: continue
                    recvSeq = opened.first + 1
                    opened.second
                } else {
                    raw
                }
                if (predicate(frame)) return@withTimeout frame
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        }
    }

    private suspend fun awaitRaw(
        timeout: Duration,
        predicate: (ByteArray) -> Boolean,
    ): ByteArray = withTimeout(timeout) {
        while (true) {
            val raw = inbound.receive()
            if (predicate(raw)) return@withTimeout raw
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    private fun assertOutboundAllowed(packet: ByteArray) {
        require(packet.size >= 2) { "Outbound packet too short" }
        val opcode = if (packet[0] == Framing.APP_PREFIX && packet.size >= 3) {
            Bytes.readU16Le(packet, 1)
        } else {
            Bytes.readU16Le(packet, 0)
        }
        ForbiddenOpcodes.requireAllowed(opcode)
    }
}
