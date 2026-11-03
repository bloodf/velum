package dev.velum.ble.wifi

import dev.velum.protocol.FeatureFlags
import dev.velum.protocol.Protocol20SessionSecret
import dev.velum.protocol.SessionCipher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow

/**
 * Cipher for Wi-Fi fast-transfer frames.
 *
 * APK 3.30.0 (Plaud parity): every WebSocket frame uses the **same BLE session**
 * key/nonce/aad (ChaCha20-Poly1305 by default, or AES-GCM when FeatureRequest bit
 * `0x08` is set), with its own sequence. Fixed nonce is required for Note Pro —
 * do not "fix" it. No Plaud cloud is involved.
 */
enum class WifiPayloadCipher {
    ChaCha20Poly1305,
    AesGcm,
    ;

    companion object {
        fun fromFeatureFlags(flags: FeatureFlags): WifiPayloadCipher =
            if (flags.wifiAesGcm) AesGcm else ChaCha20Poly1305
    }
}

/** Join the device SoftAP (phone becomes a client of the recorder's AP). */
interface WifiSoftApJoiner {
    suspend fun join(ssid: String, password: String, ssidIsPattern: Boolean = false)
    suspend fun leave()
}

/**
 * Phone-side **WebSocket** server the device connects to after SoftAP join.
 * Official Plaud app listens on port **8081** (`org.java_websocket`).
 */
interface WifiTransferServer {
    /**
     * @param bindHost SoftAP interface host when known (e.g. `"192.168.43.1"`);
     *   null = platform default; JVM fake uses loopback.
     * @param preamble required first binary WS message before encrypted frames.
     */
    suspend fun start(
        port: Int = DEFAULT_PORT,
        bindHost: String? = null,
        preamble: ByteArray = WifiTransferPreamble.defaultToken(),
    )

    suspend fun stop()

    /** Encrypted frames from the device (still sealed — open with [WifiFrameCipher]), and [PEER_CLOSED_FRAME]. */
    fun incomingEncryptedFrames(): Flow<ByteArray>

    suspend fun sendEncryptedFrame(bytes: ByteArray)

    /**
     * Fixed codes for events that make the Wi-Fi path
     * unusable before [WifiTransferCoordinator]'s timeout. Hot: subscribe before [start]. The default never emits.
     */
    fun pathFailures(): Flow<String> = flow { awaitCancellation() }

    companion object {
        /** The authenticated device closed its WebSocket. */
        const val FAILURE_PEER_CLOSED: String = "wifi_peer_closed"

        /** A frame was dropped because the consumer fell behind the receive buffer; the stream has a gap. */
        const val FAILURE_FRAME_OVERFLOW: String = "wifi_frame_overflow"

        /**
         * Put in [incomingEncryptedFrames] after a peer close, so everything received before the close is handled
         * first: a rejected frame or a complete file always beats it, however slow the consumer. Compared by
         * identity, so a device sending an empty frame can't forge it. [WifiFastTransferSession.openIncoming] turns
         * it into [FAILURE_PEER_CLOSED]. With no consumer to read it, servers use [pathFailures] instead.
         */
        val PEER_CLOSED_FRAME: ByteArray = ByteArray(0)

        const val DEFAULT_PORT: Int = 8081
    }
}

interface WifiFrameCipher {
    val mode: WifiPayloadCipher
    fun seal(sequence: Long, packet: ByteArray): ByteArray
    fun open(sequence: Long, ciphertextAndTag: ByteArray): Pair<Long, ByteArray>?
}

/**
 * ChaCha20-Poly1305 Wi-Fi frames via the BLE [SessionCipher] (fixed key/nonce/aad —
 * Plaud session parity).
 */
class BleSessionWifiFrameCipher(
    private val cipher: SessionCipher,
    private val secret: Protocol20SessionSecret,
) : WifiFrameCipher {
    override val mode: WifiPayloadCipher = WifiPayloadCipher.ChaCha20Poly1305

    override fun seal(sequence: Long, packet: ByteArray): ByteArray =
        cipher.sealPacket(secret, sequence, packet)

    override fun open(sequence: Long, ciphertextAndTag: ByteArray): Pair<Long, ByteArray>? =
        cipher.openPacket(secret, sequence, ciphertextAndTag)
}

/** SoftAP joiner that refuses to run (JVM / desktop tests use [FakeWifiSoftApJoiner]). */
object UnsupportedWifiSoftApJoiner : WifiSoftApJoiner {
    override suspend fun join(ssid: String, password: String, ssidIsPattern: Boolean) {
        error("SoftAP join is Android-only — use FakeWifiSoftApJoiner in JVM tests")
    }

    override suspend fun leave() = Unit
}

/**
 * Coordinates SoftAP + WebSocket :8081 + frame cipher (Plaud APK parity).
 * Never contacts Plaud servers.
 */
class WifiFastTransferSession(
    private val softAp: WifiSoftApJoiner,
    val server: WifiTransferServer,
    val frameCipher: WifiFrameCipher,
) {
    private var sendSeq: Long = 0
    private var recvSeq: Long = 0

    suspend fun start(
        ssid: String,
        password: String,
        port: Int = WifiTransferServer.DEFAULT_PORT,
        ssidIsPattern: Boolean = false,
        preamble: ByteArray = WifiTransferPreamble.defaultToken(),
        bindHost: String? = null,
    ) {
        // Start WS listener first so the device can connect as soon as SoftAP is up.
        server.start(port = port, bindHost = bindHost, preamble = preamble)
        softAp.join(ssid, password, ssidIsPattern)
        sendSeq = 0
        recvSeq = 0
    }

    suspend fun stop() {
        runCatching { softAp.leave() }
        server.stop()
    }

    suspend fun sendPacket(packet: ByteArray) {
        val sealed = frameCipher.seal(sendSeq, packet)
        sendSeq += 1
        server.sendEncryptedFrame(sealed)
    }

    /** Null for a frame that doesn't open. Throws if the peer closed: see [WifiTransferServer.PEER_CLOSED_FRAME]. */
    fun openIncoming(ciphertextAndTag: ByteArray): ByteArray? {
        if (ciphertextAndTag === WifiTransferServer.PEER_CLOSED_FRAME) {
            throw WifiTransferFailure(WifiTransferServer.FAILURE_PEER_CLOSED)
        }
        val opened = frameCipher.open(recvSeq, ciphertextAndTag) ?: return null
        recvSeq = opened.first + 1
        return opened.second
    }
}
