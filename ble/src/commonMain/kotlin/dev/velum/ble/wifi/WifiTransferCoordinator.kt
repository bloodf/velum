package dev.velum.ble.wifi

import dev.velum.protocol.DeviceCapabilities
import dev.velum.protocol.DeviceCapabilityRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Tries SoftAP + WebSocket :8081 fast transfer; on any failure falls back to [bleDownload].
 *
 * Phone runs WS server on 8081; device joins SoftAP and pushes frames encrypted with the
 * BLE session key/aad (ChaCha20-Poly1305 or AES-GCM when feature bit 0x08) and a **unique
 * 96-bit nonce per frame**. BLE fallback remains for SoftAP/cipher failures.
 *
 * Gated by [DeviceCapabilityRegistry.supportsWifiFastPath] — Note (888) and handshake
 * `wifiSupported=false` skip SoftAP and go straight to BLE.
 */
class WifiTransferCoordinator(
    private val sessionFactory: () -> WifiFastTransferSession,
    private val bleDownload: suspend () -> ByteArray,
    private val wifiTimeout: Duration = 45.seconds,
    private val capabilities: DeviceCapabilities? = null,
) {
    sealed class Outcome {
        data class Wifi(val bytes: ByteArray) : Outcome() {
            override fun equals(other: Any?): Boolean =
                other is Wifi && bytes.contentEquals(other.bytes)

            override fun hashCode(): Int = bytes.contentHashCode()
        }

        data class BleFallback(val bytes: ByteArray, val reason: String) : Outcome() {
            override fun equals(other: Any?): Boolean =
                other is BleFallback && bytes.contentEquals(other.bytes) && reason == other.reason

            override fun hashCode(): Int = 31 * bytes.contentHashCode() + reason.hashCode()
        }
    }

    /**
     * [assemble] consumes opened plaintext frames until it returns non-null payload
     * (e.g. full session file). Runs under [wifiTimeout]; failures → BLE.
     */
    suspend fun transferOrFallback(
        ssid: String,
        password: String,
        ssidIsPattern: Boolean = false,
        port: Int = WifiTransferServer.DEFAULT_PORT,
        preamble: ByteArray = WifiTransferPreamble.defaultToken(),
        bindHost: String? = null,
        assemble: suspend (WifiFastTransferSession) -> ByteArray,
    ): Outcome {
        val caps = capabilities
        if (caps != null && !DeviceCapabilityRegistry.supportsWifiFastPath(caps)) {
            val ble = bleDownload()
            return Outcome.BleFallback(ble, "wifi_unsupported_for_${caps.model.name}")
        }
        val session = sessionFactory()
        return try {
            withTimeout(wifiTimeout) {
                session.start(
                    ssid = ssid,
                    password = password,
                    port = port,
                    ssidIsPattern = ssidIsPattern,
                    preamble = preamble,
                    bindHost = bindHost,
                )
                val bytes = assemble(session)
                Outcome.Wifi(bytes)
            }
        } catch (t: CancellationException) {
            if (t is TimeoutCancellationException) {
                val ble = bleDownload()
                Outcome.BleFallback(ble, "wifi_timeout: ${t.message}")
            } else {
                throw t
            }
        } catch (t: Throwable) {
            val ble = bleDownload()
            Outcome.BleFallback(ble, t.message ?: t::class.simpleName ?: "wifi_failed")
        } finally {
            runCatching { session.stop() }
        }
    }

    /** Collect first sealed frame, open it, return plaintext (demo / tests). */
    suspend fun receiveOnePlainFrame(session: WifiFastTransferSession): ByteArray {
        val sealed = session.server.incomingEncryptedFrames().first()
        return session.openIncoming(sealed)
            ?: error("Failed to open Wi-Fi frame (seq/cipher mismatch)")
    }
}

/**
 * First WebSocket binary message must equal this token (Velum + fake device).
 * Plaud's app authenticates via the SoftAP WPA2 + active BLE session; we add an
 * explicit preamble so the phone never accepts a random SoftAP client.
 */
object WifiTransferPreamble {
    private const val MAGIC = "VELUM-WIFI-v1"

    fun defaultToken(bindToken: String = "local"): ByteArray =
        "$MAGIC:$bindToken".encodeToByteArray()

    fun matches(actual: ByteArray, expected: ByteArray): Boolean =
        actual.contentEquals(expected)
}
