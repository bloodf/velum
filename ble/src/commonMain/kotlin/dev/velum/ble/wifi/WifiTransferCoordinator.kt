package dev.velum.ble.wifi

import dev.velum.protocol.DeviceCapabilities
import dev.velum.protocol.DeviceCapabilityRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** A Wi-Fi path failure whose fixed [code] is safe to show in a fallback reason (never put identity data in it). */
class WifiTransferFailure(val code: String) : IllegalStateException(code)

/**
 * Tries SoftAP + WebSocket :8081 fast transfer; on any failure falls back to [bleDownload].
 *
 * Plaud parity: phone runs WS server on 8081; device joins SoftAP and pushes frames encrypted
 * with the BLE session key/nonce/aad (ChaCha20-Poly1305 or AES-GCM when feature bit 0x08).
 * Fixed nonce is **required** for Note Pro interoperability — not optional debt.
 *
 * Gated by [DeviceCapabilityRegistry.supportsWifiFastPath] — Note (888) and handshake
 * `wifiSupported=false` skip SoftAP and go straight to BLE.
 */
class WifiTransferCoordinator(
    private val sessionFactory: () -> WifiFastTransferSession,
    private val bleDownload: suspend () -> ByteArray,
    private val wifiTimeout: Duration = 45.seconds,
    private val capabilities: DeviceCapabilities? = null,
    /** After the device closes its socket, how long [assemble] may still finish from frames already received. */
    private val peerClosedGrace: Duration = 2.seconds,
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
                coroutineScope {
                    // Subscribe before start so no early failure is missed.
                    val failure = async(start = CoroutineStart.UNDISPATCHED) {
                        session.server.pathFailures().first()
                    }
                    val work = async {
                        session.start(
                            ssid = ssid,
                            password = password,
                            port = port,
                            ssidIsPattern = ssidIsPattern,
                            preamble = preamble,
                            bindHost = bindHost,
                        )
                        Outcome.Wifi(assemble(session))
                    }
                    try {
                        select<Outcome> {
                            work.onAwait { it }
                            failure.onAwait { code ->
                                // A close right after the last frame is normal: let assemble drain what arrived.
                                val drained = if (code == WifiTransferServer.FAILURE_PEER_CLOSED) {
                                    withTimeoutOrNull(peerClosedGrace) { work.await() }
                                } else {
                                    null
                                }
                                drained ?: throw WifiTransferFailure(code)
                            }
                        }
                    } finally {
                        failure.cancel()
                        work.cancel()
                    }
                }
            }
        } catch (t: CancellationException) {
            if (t is TimeoutCancellationException) {
                val ble = bleDownload()
                Outcome.BleFallback(ble, "wifi_timeout")
            } else {
                throw t
            }
        } catch (t: Throwable) {
            val ble = bleDownload()
            // Only a WifiTransferFailure's fixed code is surfaced; any other message could carry sensitive data.
            Outcome.BleFallback(ble, (t as? WifiTransferFailure)?.code ?: t::class.simpleName ?: "wifi_failed")
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
