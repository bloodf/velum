package dev.velum.ble.wifi

import dev.velum.protocol.FeatureFlags
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/**
 * Tries SoftAP + TCP :8081 when FeatureRequest bit 0x08 is set; on any Wi-Fi
 * failure falls back to BLE [bleDownload].
 *
 * Fixed AEAD nonce is Plaud parity debt (Note Pro SoftAP). Token preamble is
 * Velum phone-listener auth.
 */
class WifiTransferCoordinator(
    private val sessionFactory: () -> WifiFastTransferSession,
    private val bleDownload: suspend () -> ByteArray,
    private val wifiTimeout: Duration = 45.seconds,
) {
    sealed class Outcome {
        data class Wifi(val bytes: ByteArray) : Outcome() {
            override fun equals(other: Any?): Boolean =
                other is Wifi && bytes.contentEquals(other.bytes)

            override fun hashCode(): Int = bytes.contentHashCode()
        }

        data class BleFallback(val bytes: ByteArray, val reason: String) : Outcome() {
            override fun equals(other: Any?): Boolean =
                other is BleFallback &&
                    bytes.contentEquals(other.bytes) &&
                    reason == other.reason

            override fun hashCode(): Int = 31 * bytes.contentHashCode() + reason.hashCode()
        }
    }

    suspend fun transferOrFallback(
        featureFlags: FeatureFlags,
        ssid: String,
        password: String,
        expectedPreamble: ByteArray,
        ssidIsPattern: Boolean = false,
        port: Int = WifiTransferServer.DEFAULT_PORT,
        bindAddress: String? = null,
        sizeBytes: Long = 0,
        assemble: (suspend (WifiFastTransferSession) -> ByteArray)? = null,
    ): Outcome {
        if (!featureFlags.wifiAesGcm) {
            return Outcome.BleFallback(bleDownload(), "feature_bit_0x08_absent")
        }
        val session = sessionFactory()
        return try {
            withTimeout(wifiTimeout) {
                session.start(
                    ssid = ssid,
                    password = password,
                    port = port,
                    ssidIsPattern = ssidIsPattern,
                    bindAddress = bindAddress,
                    expectedPreamble = expectedPreamble,
                )
                val bytes = if (assemble != null) {
                    assemble(session)
                } else {
                    collectUntilSize(session, sizeBytes)
                }
                Outcome.Wifi(bytes)
            }
        } catch (t: CancellationException) {
            if (t is TimeoutCancellationException) {
                Outcome.BleFallback(bleDownload(), "wifi_timeout: ${t.message}")
            } else {
                throw t
            }
        } catch (t: Throwable) {
            Outcome.BleFallback(bleDownload(), t.message ?: t::class.simpleName ?: "wifi_failed")
        } finally {
            runCatching { session.stop() }
        }
    }

    companion object {
        suspend fun collectUntilSize(
            session: WifiFastTransferSession,
            sizeBytes: Long,
        ): ByteArray {
            val chunks = ArrayList<ByteArray>()
            var total = 0
            while (sizeBytes <= 0 || total < sizeBytes) {
                val sealed = session.server.incomingEncryptedFrames().first()
                val chunk = session.openIncoming(sealed)
                    ?: error("Wi-Fi frame decrypt failed")
                if (chunk.isEmpty()) break
                chunks += chunk
                total += chunk.size
                if (sizeBytes > 0 && total >= sizeBytes) break
            }
            if (sizeBytes > 0 && total < sizeBytes) {
                error("Wi-Fi transfer incomplete: got $total < expected $sizeBytes")
            }
            val want = if (sizeBytes > 0) sizeBytes.toInt() else total
            val out = ByteArray(want)
            var offset = 0
            for (chunk in chunks) {
                val n = minOf(chunk.size, want - offset)
                chunk.copyInto(out, offset, 0, n)
                offset += n
                if (offset >= want) break
            }
            return out
        }
    }
}
