package dev.velum.ble.client

import dev.velum.ble.BleTransport
import dev.velum.protocol.Battery
import dev.velum.protocol.BatteryReading
import dev.velum.protocol.Bytes
import dev.velum.protocol.Commands
import dev.velum.protocol.DeviceIdentityKeys
import dev.velum.protocol.CommonSettings
import dev.velum.protocol.ForbiddenOpcodes
import dev.velum.protocol.ForbiddenSettings
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
import dev.velum.ble.wifi.WifiFastTransferSession
import dev.velum.ble.wifi.WifiTransferCoordinator
import dev.velum.ble.wifi.WifiTransferPreamble
import dev.velum.ble.wifi.WifiTransferServer
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

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
    /** Where notifications are collected. Defaults to the first caller's scope, which then waits for [close]. */
    private val notificationScope: CoroutineScope? = null,
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
        val scope = notificationScope ?: CoroutineScope(coroutineContext)
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


    /**
     * SoftAP + WebSocket :8081 fast path (Plaud parity); on any Wi-Fi failure
     * falls back to BLE [downloadSession]. [assemble] collects sealed WS frames
     * into the session payload (see [WifiTransferCoordinator]).
     */
    suspend fun downloadSessionPreferWifi(
        sessionId: Long,
        sizeBytes: Long,
        wifiSessionFactory: () -> WifiFastTransferSession,
        ssid: String,
        password: String,
        preamble: ByteArray = WifiTransferPreamble.defaultToken(),
        bindHost: String? = null,
        port: Int = WifiTransferServer.DEFAULT_PORT,
        ssidIsPattern: Boolean = false,
        wifiTimeout: Duration = 45.seconds,
        assemble: suspend (WifiFastTransferSession) -> ByteArray,
    ): WifiTransferCoordinator.Outcome {
        val coordinator = WifiTransferCoordinator(
            sessionFactory = wifiSessionFactory,
            bleDownload = { downloadSession(sessionId, sizeBytes) },
            wifiTimeout = wifiTimeout,
        )
        return coordinator.transferOrFallback(
            ssid = ssid,
            password = password,
            ssidIsPattern = ssidIsPattern,
            port = port,
            preamble = preamble,
            bindHost = bindHost,
            assemble = assemble,
        )
    }

    /**
     * Download a session. Large transfers stream to a temp file when available,
     * then return bytes (still capped by [MAX_SESSION_DOWNLOAD_BYTES]).
     * Prefer [downloadSessionTo] when the caller already has a disk sink.
     *
     * [onProgress] reports cumulative received bytes (estimate progress as
     * received / sizeBytes when sizeBytes > 0).
     */
    suspend fun downloadSession(
        sessionId: Long,
        sizeBytes: Long,
        onProgress: ((receivedBytes: Long) -> Unit)? = null,
    ): ByteArray {
        require(sizeBytes in 0..MAX_SESSION_DOWNLOAD_BYTES) {
            "Session sizeBytes=$sizeBytes exceeds cap of $MAX_SESSION_DOWNLOAD_BYTES"
        }
        val useFile = sizeBytes <= 0L || sizeBytes >= STREAM_TO_FILE_THRESHOLD_BYTES
        val temp = if (useFile) createTempSessionFile() else null
        if (temp != null) {
            try {
                downloadSessionTo(sessionId, sizeBytes, temp, onProgress)
                require(temp.size() <= MAX_SESSION_DOWNLOAD_BYTES) {
                    "Session download exceeded ${MAX_SESSION_DOWNLOAD_BYTES}-byte cap"
                }
                return temp.readAll()
            } finally {
                temp.delete()
            }
        }
        val buffer = SessionDownloadBuffer(MAX_SESSION_DOWNLOAD_BYTES)
        downloadSessionTo(sessionId, sizeBytes, SessionByteSink { buffer.write(it) }, onProgress)
        return buffer.toByteArray()
    }

    /**
     * Stream session bytes from [startOffset] into [sink] without retaining the full payload.
     *
     * Chunks must be contiguous, because decryption is positional and a gapped file is useless: a forward
     * gap, a partial overlap, an overrun of [sizeBytes] or a short transfer throws
     * [TransferIntegrityException] (nothing past the failure is written). A chunk entirely below the next
     * offset is a repeat and is skipped; a chunk with another session's id is a stray from an interrupted
     * transfer and is ignored. Absolute offsets come from community protocol notes (docs/protocol) and are
     * unverified on the owner's device (#6).
     *
     * [onProgress] reports the absolute byte offset reached. Returns the device's stop CRC, or
     * [NO_DEVICE_CRC] when there is none: the stop reply is best-effort, so a transfer holding all its
     * bytes is never lost to a missing tail. Verifying the CRC is the caller's job.
     */
    suspend fun downloadSessionTo(
        sessionId: Long,
        sizeBytes: Long,
        sink: SessionByteSink,
        onProgress: ((receivedBytes: Long) -> Unit)? = null,
        startOffset: Long = 0L,
    ): Int {
        require(sizeBytes in 0..MAX_SESSION_DOWNLOAD_BYTES) {
            "Session sizeBytes=$sizeBytes exceeds cap of $MAX_SESSION_DOWNLOAD_BYTES"
        }
        require(startOffset >= 0 && (sizeBytes == 0L || startOffset <= sizeBytes)) { "startOffset=$startOffset out of range" }
        val start = Sessions.encodeSyncStart(sessionId, startOffset, sizeBytes)
        val startPayload = transactCommand(Commands.SYNC_FILE_START, start)
        val startRsp = Sessions.parseSyncStartResponse(startPayload)
        require(!startRsp.refused) { "Sync start refused status=${startRsp.status}" }

        var next = startOffset
        try {
            while (true) {
                val raw = awaitDecryptedFrame(responseTimeout) { true }
                when (val inboundPacket = Framing.decodeInbound(raw)) {
                    is InboundPacket.RecordingData -> {
                        when (val data = Sessions.parseRecordingData(raw, protocolVersion)) {
                            is RecordingDataPacket.Chunk -> {
                                val end = data.offset + data.data.size
                                val ignored = isStray(data.sessionId, sessionId) || end <= next
                                if (!ignored) {
                                    if (data.offset != next) {
                                        throw TransferIntegrityException("Chunk at offset ${data.offset}, expected $next")
                                    }
                                    if (sizeBytes > 0 && end > sizeBytes) {
                                        throw TransferIntegrityException("Chunk ending at $end overruns size $sizeBytes")
                                    }
                                    require(end <= MAX_SESSION_DOWNLOAD_BYTES) {
                                        "Session download exceeded ${MAX_SESSION_DOWNLOAD_BYTES}-byte cap"
                                    }
                                    sink.write(data.data)
                                    next = end
                                    onProgress?.invoke(next)
                                }
                            }
                            is RecordingDataPacket.End -> if (!isStray(data.sessionId, sessionId)) break
                        }
                    }
                    is InboundPacket.CommandResponse -> {
                        if (isStopReply(inboundPacket.cmd)) break
                    }
                    else -> Unit
                }
                if (sizeBytes > 0 && next >= sizeBytes) break
            }
            if (sizeBytes > 0 && next != sizeBytes) {
                throw TransferIntegrityException("Transfer ended at offset $next of $sizeBytes")
            }
        } catch (e: Exception) {
            sendStopBestEffort()
            throw e
        }
        return stopCrcBestEffort()
    }

    private fun isStray(packetSessionId: Long?, sessionId: Long) = packetSessionId != null && packetSessionId != sessionId

    /** Receive-only: a device may answer a stop as 30. Velum must never SEND 30, which is delete in our docs. */
    private fun isStopReply(cmd: Int) = cmd == Commands.SYNC_FILE_STOP || cmd == Commands.DELETE_FILE

    /** All bytes are stored, so nothing here may fail the transfer, except the caller's own cancellation. */
    private suspend fun stopCrcBestEffort(): Int = try {
        writeOutbound(Sessions.encodeSyncStop())
        val raw = awaitDecryptedFrame(responseTimeout) { frame ->
            val decoded = runCatching { Framing.decodeInbound(frame) }.getOrNull()
            decoded is InboundPacket.CommandResponse && isStopReply(decoded.cmd)
        }
        val reply = Framing.decodeInbound(raw) as InboundPacket.CommandResponse
        if (reply.cmd == Commands.SYNC_FILE_STOP) Sessions.parseSyncStopResponse(reply.payload).crc else NO_DEVICE_CRC
    } catch (_: TimeoutCancellationException) {
        // Our own timeout leaves this job active; an enclosing timeout or cancellation does not.
        coroutineContext.ensureActive()
        NO_DEVICE_CRC
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        NO_DEVICE_CRC
    }

    /** Ask the device to stop streaming when a transfer is abandoned; no reply is awaited. */
    private suspend fun sendStopBestEffort() {
        withContext(NonCancellable) {
            runCatching { withTimeoutOrNull(responseTimeout) { writeOutbound(Sessions.encodeSyncStop()) } }
        }
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
                val frame = if (sec != null) openInOrder(sec, raw) ?: continue else raw
                if (predicate(frame)) return@withTimeout frame
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        }
    }

    /**
     * Receiver rule for the BLE link (see [SessionCipher.openPacket]): drop packets that do not
     * authenticate and replays (seq below the next expected), and accept any later seq. Requiring the exact
     * next seq would stall everything after one lost packet; a lost data packet then shows up as an offset
     * gap in [downloadSessionTo]. The Wi-Fi path keeps [SessionCipher.openPacket]'s exact match on purpose.
     */
    private fun openInOrder(sec: Protocol20SessionSecret, raw: ByteArray): ByteArray? {
        val plain = cipher.chacha20Poly1305Open(sec.key, sec.nonce, sec.aad, raw) ?: return null
        if (plain.size < 4) return null
        val seq = Bytes.readU32Le(plain, 0)
        if (seq < recvSeq) return null
        recvSeq = seq + 1
        return plain.copyOfRange(4, plain.size)
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
        val hasAppPrefix = packet[0] == Framing.APP_PREFIX && packet.size >= 3
        val opcode = if (hasAppPrefix) {
            Bytes.readU16Le(packet, 1)
        } else {
            Bytes.readU16Le(packet, 0)
        }
        ForbiddenOpcodes.requireAllowed(opcode)
        if (opcode == CommonSettings.CMD) {
            val paramsOffset = if (hasAppPrefix) 3 else 2
            val params = if (packet.size > paramsOffset) {
                packet.copyOfRange(paramsOffset, packet.size)
            } else {
                ByteArray(0)
            }
            // Cmd 8 read is [settingId]; write is [settingId][value…].
            // Only writes of forbidden settings are rejected (reads stay allowed).
            if (params.size > 1) {
                ForbiddenSettings.requireWritable(params[0].toInt() and 0xFF)
            }
        }
    }

    companion object {
        /** Stop CRC value meaning "none", as in the only real capture (docs/protocol/README.md). */
        const val NO_DEVICE_CRC: Int = 0xFFFF

        /** Hard cap for a single session download (512 MiB). */
        const val MAX_SESSION_DOWNLOAD_BYTES: Long = 512L * 1024 * 1024

        /** Stream to a temp file when declared size is unknown or at/above this. */
        const val STREAM_TO_FILE_THRESHOLD_BYTES: Long = 8L * 1024 * 1024
    }
}
