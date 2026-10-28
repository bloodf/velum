package dev.velum.ble.client

import dev.velum.ble.BleTransport
import dev.velum.ble.PlaudBleTransport
import dev.velum.ble.PlaudEncryptedSession
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
import dev.velum.protocol.PlaudOutboundGuard
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
import kotlinx.coroutines.CompletableDeferred
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
    ble: BleTransport,
    private val cipher: SessionCipher,
    private val keys: DeviceIdentityKeys,
    private val responseTimeout: Duration = 10.seconds,
    private val protocolVersionHint: Int = 20,
) {
    /** Plaud write/notify facade over the generic multi-GATT [BleTransport]. */
    private val ble = PlaudBleTransport(ble)

    private val inbound = Channel<ByteArray>(capacity = 128)
    private val writeMutex = Mutex()
    private val demuxMutex = Mutex()
    private var notifyJob: Job? = null
    private var demuxJob: Job? = null

    /** Raw unlock-path waiters (FE11 / FE12) — plaintext notify frames. */
    private val rawWaiters = mutableListOf<FrameWaiter>()
    private val rawPending = ArrayDeque<ByteArray>()

    /** Decrypted link-path waiters — non-matching frames stay buffered. */
    private val decryptedWaiters = mutableListOf<FrameWaiter>()
    private val decryptedPending = ArrayDeque<ByteArray>()

    private var secret: Protocol20SessionSecret? = null
    /** Encrypted link owned by `:ble`; seals plaintext inside the module. */
    private var encryptedSession: PlaudEncryptedSession? = null
    private var recvSeq: Long = 0
    private var protocolVersion: Int = 20

    val sessionSecret: Protocol20SessionSecret? get() = secret
    val isUnlocked: Boolean get() = secret != null
    val linkProtocolVersion: Int get() = protocolVersion

    suspend fun start() {
        if (notifyJob != null) return
        val scope = CoroutineScope(coroutineContext)
        notifyJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            ble.notifications().collect { inbound.send(it) }
        }
        demuxJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            while (true) {
                val raw = inbound.receive()
                dispatchInbound(raw)
            }
        }
    }

    fun close() {
        notifyJob?.cancel()
        demuxJob?.cancel()
        notifyJob = null
        demuxJob = null
        inbound.close()
        failWaiters(rawWaiters, rawPending)
        failWaiters(decryptedWaiters, decryptedPending)
    }

    private fun failWaiters(
        waiters: MutableList<FrameWaiter>,
        pending: ArrayDeque<ByteArray>,
    ) {
        waiters.toList().forEach { it.deferred.cancel() }
        waiters.clear()
        pending.clear()
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
            encryptedSession = ble.openEncryptedSession(
                cipher = cipher,
                secret = parsed,
                initialSendSeq = 2,
            )
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

    /** Stream session bytes into [sink] without retaining the full payload in a Byte list. */
    suspend fun downloadSessionTo(
        sessionId: Long,
        sizeBytes: Long,
        sink: SessionByteSink,
        onProgress: ((receivedBytes: Long) -> Unit)? = null,
    ) {
        require(sizeBytes in 0..MAX_SESSION_DOWNLOAD_BYTES) {
            "Session sizeBytes=$sizeBytes exceeds cap of $MAX_SESSION_DOWNLOAD_BYTES"
        }
        val start = Sessions.encodeSyncStart(sessionId, 0, sizeBytes)
        val startPayload = transactCommand(Commands.SYNC_FILE_START, start)
        val startRsp = Sessions.parseSyncStartResponse(startPayload)
        require(!startRsp.refused) { "Sync start refused status=${startRsp.status}" }

        var written = 0L
        try {
            while (true) {
                val raw = awaitDecryptedFrame(responseTimeout) { true }
                when (val inboundPacket = Framing.decodeInbound(raw)) {
                    is InboundPacket.RecordingData -> {
                        when (val data = Sessions.parseRecordingData(raw, protocolVersion)) {
                            is RecordingDataPacket.Chunk -> {
                                require(written + data.data.size <= MAX_SESSION_DOWNLOAD_BYTES) {
                                    "Session download exceeded ${MAX_SESSION_DOWNLOAD_BYTES}-byte cap"
                                }
                                sink.write(data.data)
                                written += data.data.size
                                onProgress?.invoke(written)
                            }
                            is RecordingDataPacket.End -> break
                        }
                    }
                    is InboundPacket.CommandResponse -> {
                        if (inboundPacket.cmd == Commands.SYNC_FILE_STOP) break
                    }
                    else -> Unit
                }
                if (sizeBytes > 0 && written >= sizeBytes) break
            }

            val stopPayload = transactCommand(Commands.SYNC_FILE_STOP, Sessions.encodeSyncStop())
            Sessions.parseSyncStopResponse(stopPayload)
        } catch (e: kotlinx.coroutines.CancellationException) {
            runCatching {
                transactCommand(Commands.SYNC_FILE_STOP, Sessions.encodeSyncStop())
            }
            throw e
        }
    }

    /**
     * Poll battery (cmd 9). Uses [Battery.parseOrNull] under the hood; truncated /
     * out-of-range payloads fail with a clear error. Prefer [readBatteryOrNull] on
     * UI paths that must treat `0xFF`-while-charging as unknown.
     */
    suspend fun readBattery(): BatteryReading =
        readBatteryOrNull()
            ?: error("Battery reading unavailable (truncated or out-of-range level)")

    /**
     * Poll battery without throwing on truncated / out-of-range payloads
     * (e.g. level `0xFF` while charging → null).
     */
    suspend fun readBatteryOrNull(): BatteryReading? {
        val packet = Battery.encodeBatteryRequest()
        val payload = transactCommand(Commands.BATTERY_LIKELY, packet)
        return Battery.parseOrNull(payload)
    }

    /**
     * Wait for the next inbound cmd-9 battery frame without sending a request.
     * Uses [Battery.parseOrNull]; non-battery frames are buffered for other waiters.
     */
    suspend fun awaitBatteryNotification(
        timeout: Duration = responseTimeout,
    ): BatteryReading? {
        val raw = awaitDecryptedFrame(timeout) { frame ->
            val decoded = runCatching { Framing.decodeInbound(frame) }.getOrNull()
            decoded is InboundPacket.CommandResponse &&
                decoded.cmd == Commands.BATTERY_LIKELY
        }
        val decoded = Framing.decodeInbound(raw) as InboundPacket.CommandResponse
        return Battery.parseOrNull(decoded.payload)
    }

    /**
     * Exposed for tests: wait for a decrypted command-response payload without
     * sending. Non-matching frames remain available to other waiters.
     */
    suspend fun awaitCommandResponseForTest(
        cmd: Int,
        timeout: Duration = responseTimeout,
    ): ByteArray {
        val raw = awaitDecryptedFrame(timeout) { frame ->
            val decoded = runCatching { Framing.decodeInbound(frame) }.getOrNull()
            decoded is InboundPacket.CommandResponse && decoded.cmd == cmd
        }
        return (Framing.decodeInbound(raw) as InboundPacket.CommandResponse).payload
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
            // Client send-path layer: reject forbidden opcodes/settings on plaintext.
            assertOutboundAllowed(packet)
            if (encryptedSession == null) {
                // Transport layer also guards plaintext on public write().
                ble.write(packet, withoutResponse = false)
            } else {
                sendEncrypted(packet)
            }
        }
    }

    /**
     * Single call site for encrypted-session writes.
     *
     * Routes through [PlaudEncryptedSession.writeEncrypted] (plaintext only;
     * `:ble` seals internally). Never call sealed-write sink APIs from here.
     */
    private suspend fun sendEncrypted(plaintext: ByteArray) {
        val session = encryptedSession
            ?: error("sendEncrypted requires an unlocked encrypted session")
        session.writeEncrypted(plaintext, withoutResponse = false)
    }

    private suspend fun dispatchInbound(raw: ByteArray) {
        val session = encryptedSession
        if (session == null) {
            offerFrame(raw, rawWaiters, rawPending)
            return
        }
        val opened = session.openPacket(recvSeq, raw) ?: return
        recvSeq = opened.first + 1
        offerFrame(opened.second, decryptedWaiters, decryptedPending)
    }

    private suspend fun offerFrame(
        frame: ByteArray,
        waiters: MutableList<FrameWaiter>,
        pending: ArrayDeque<ByteArray>,
    ) {
        demuxMutex.withLock {
            val idx = waiters.indexOfFirst { it.predicate(frame) }
            if (idx >= 0) {
                val waiter = waiters.removeAt(idx)
                waiter.deferred.complete(frame)
            } else {
                pending.addLast(frame.copyOf())
            }
        }
    }

    private suspend fun awaitDecryptedFrame(
        timeout: Duration,
        predicate: (ByteArray) -> Boolean,
    ): ByteArray = awaitFromDemux(timeout, predicate, decryptedWaiters, decryptedPending)

    private suspend fun awaitRaw(
        timeout: Duration,
        predicate: (ByteArray) -> Boolean,
    ): ByteArray = awaitFromDemux(timeout, predicate, rawWaiters, rawPending)

    private suspend fun awaitFromDemux(
        timeout: Duration,
        predicate: (ByteArray) -> Boolean,
        waiters: MutableList<FrameWaiter>,
        pending: ArrayDeque<ByteArray>,
    ): ByteArray = withTimeout(timeout) {
        val deferred = CompletableDeferred<ByteArray>()
        demuxMutex.withLock {
            val idx = pending.indexOfFirst(predicate)
            if (idx >= 0) {
                return@withTimeout pending.removeAt(idx)
            }
            waiters += FrameWaiter(predicate, deferred)
        }
        try {
            deferred.await()
        } catch (t: Throwable) {
            demuxMutex.withLock {
                waiters.removeAll { it.deferred === deferred }
            }
            throw t
        }
    }

    private fun assertOutboundAllowed(packet: ByteArray) {
        PlaudOutboundGuard.requireAllowed(packet)
    }

    private class FrameWaiter(
        val predicate: (ByteArray) -> Boolean,
        val deferred: CompletableDeferred<ByteArray>,
    )

    companion object {
        /** Hard cap for a single session download (512 MiB). */
        const val MAX_SESSION_DOWNLOAD_BYTES: Long = 512L * 1024 * 1024

        /** Stream to a temp file when declared size is unknown or at/above this. */
        const val STREAM_TO_FILE_THRESHOLD_BYTES: Long = 8L * 1024 * 1024
    }
}
