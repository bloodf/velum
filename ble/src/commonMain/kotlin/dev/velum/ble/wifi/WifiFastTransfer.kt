package dev.velum.ble.wifi

import dev.velum.protocol.FeatureFlags
import kotlinx.coroutines.flow.Flow

/**
 * Cipher for Wi-Fi fast-transfer frames.
 *
 * SoftAP frames use the BLE session **key** and **aad**, ChaCha20-Poly1305 by
 * default (or AES-GCM when FeatureRequest bit `0x08` is set), with a **unique
 * 96-bit nonce per message** (CSPRNG). Wire layout: `nonce[12] ‖ ciphertext‖tag`.
 * Plaintext = `seq_u32_le ‖ packet`. No Plaud cloud is involved.
 *
 * BLE link packets still use Plaud's fixed session nonce via [SessionCipher];
 * SoftAP deliberately diverges so (key, nonce) pairs are never reused on Wi-Fi.
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

    /** Encrypted frames from the device (still sealed — open with [WifiFrameCipher]). */
    fun incomingEncryptedFrames(): Flow<ByteArray>

    suspend fun sendEncryptedFrame(bytes: ByteArray)

    companion object {
        const val DEFAULT_PORT: Int = 8081
    }
}

interface WifiFrameCipher {
    val mode: WifiPayloadCipher
    fun seal(sequence: Long, packet: ByteArray): ByteArray
    fun open(sequence: Long, ciphertextAndTag: ByteArray): Pair<Long, ByteArray>?
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

    fun openIncoming(ciphertextAndTag: ByteArray): ByteArray? {
        val opened = frameCipher.open(recvSeq, ciphertextAndTag) ?: return null
        recvSeq = opened.first + 1
        return opened.second
    }
}
