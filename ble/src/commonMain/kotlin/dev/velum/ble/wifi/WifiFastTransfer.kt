package dev.velum.ble.wifi

import dev.velum.protocol.FeatureFlags
import dev.velum.protocol.Protocol20SessionSecret
import dev.velum.protocol.SessionCipher
import kotlinx.coroutines.flow.Flow

/**
 * Cipher for Wi-Fi fast-transfer frames.
 *
 * APK 3.30.0: every WebSocket frame uses the **same BLE session** key/nonce/aad
 * (ChaCha20-Poly1305 by default, or AES-GCM when FeatureRequest bit 0x08 is set),
 * with its own sequence. No Plaud cloud is involved.
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
 * Phone-side WebSocket server the device connects to after SoftAP join.
 * Official app listens on port **8081**.
 */
interface WifiTransferServer {
    suspend fun start(port: Int = DEFAULT_PORT)
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

/**
 * ChaCha20-Poly1305 Wi-Fi frames via the BLE [SessionCipher] (fixed key/nonce/aad).
 * AES-GCM mode is provided by platform JCA helpers ([createWifiFrameCipher]).
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

/**
 * SoftAP joiner that refuses to run (JVM / desktop tests skip SoftAP).
 */
object UnsupportedWifiSoftApJoiner : WifiSoftApJoiner {
    override suspend fun join(ssid: String, password: String, ssidIsPattern: Boolean) {
        error("SoftAP join is Android-only — JVM tests skip Wi-Fi SoftAP")
    }

    override suspend fun leave() = Unit
}

/**
 * Coordinates local SoftAP + WS :8081 + frame cipher without contacting Plaud servers.
 */
class WifiFastTransferSession(
    private val softAp: WifiSoftApJoiner,
    private val server: WifiTransferServer,
    val frameCipher: WifiFrameCipher,
) {
    private var sendSeq: Long = 0
    private var recvSeq: Long = 0

    suspend fun start(
        ssid: String,
        password: String,
        port: Int = WifiTransferServer.DEFAULT_PORT,
        ssidIsPattern: Boolean = false,
    ) {
        server.start(port)
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
