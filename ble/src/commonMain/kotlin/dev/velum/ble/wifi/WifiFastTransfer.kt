package dev.velum.ble.wifi

import dev.velum.protocol.FeatureFlags
import dev.velum.protocol.Protocol20SessionSecret
import dev.velum.protocol.SessionCipher
import kotlinx.coroutines.flow.Flow

/**
 * Cipher for Wi-Fi fast-transfer frames.
 *
 * APK 3.30.0: SoftAP frames use the same BLE session key/nonce/aad
 * (ChaCha20-Poly1305 by default, or AES-GCM when FeatureRequest bit 0x08 is set),
 * with per-frame sequence. Fixed nonce is Plaud protocol parity debt — required for
 * Note Pro SoftAP interoperability when hardware arrives; do not rotate per frame.
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

interface WifiSoftApJoiner {
    suspend fun join(ssid: String, password: String, ssidIsPattern: Boolean = false)
    suspend fun leave()
}

/**
 * Phone-side TCP listener after SoftAP join. Plaud uses WebSocket on :8081;
 * Velum uses length-prefixed TCP on the same port with the same session cipher.
 * Token preamble is Velum-side phone-listener auth.
 */
interface WifiTransferServer {
    suspend fun start(
        port: Int = DEFAULT_PORT,
        bindAddress: String? = null,
        expectedPreamble: ByteArray,
    )

    suspend fun stop()

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

object UnsupportedWifiSoftApJoiner : WifiSoftApJoiner {
    override suspend fun join(ssid: String, password: String, ssidIsPattern: Boolean) {
        error("SoftAP join is Android-only — use FakeWifiSoftApJoiner in JVM tests")
    }

    override suspend fun leave() = Unit
}

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
        bindAddress: String? = null,
        expectedPreamble: ByteArray,
    ) {
        server.start(port = port, bindAddress = bindAddress, expectedPreamble = expectedPreamble)
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

/**
 * Session-token preamble: shared magic + bind-token, or session-key-derived token.
 */
object WifiTransferPreamble {
    private val MAGIC = byteArrayOf('V'.code.toByte(), 'L'.code.toByte(), 'W'.code.toByte(), 'F'.code.toByte())

    fun fromBindToken(bindToken: String): ByteArray {
        require(bindToken.isNotEmpty()) { "bind token required for Wi-Fi preamble" }
        return MAGIC + bindToken.encodeToByteArray()
    }

    fun fromSessionKey(sessionKey: ByteArray): ByteArray {
        require(sessionKey.isNotEmpty()) { "session key required for Wi-Fi preamble" }
        val n = minOf(16, sessionKey.size)
        return MAGIC + sessionKey.copyOf(n)
    }

    fun matches(actual: ByteArray, expected: ByteArray): Boolean =
        actual.contentEquals(expected)
}
