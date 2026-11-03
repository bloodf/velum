package dev.velum.ble.wifi

import dev.velum.ble.crypto.createSessionCipher
import dev.velum.protocol.Protocol20SessionSecret
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** The fallback reason is a fixed code or a class name, never an arbitrary exception message. */
class WifiFailureReasonTest {
    private val secret = Protocol20SessionSecret(ByteArray(32), ByteArray(12), ByteArray(12))

    private fun reasonFor(failure: Throwable): String = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val outcome = WifiTransferCoordinator(
            sessionFactory = {
                WifiFastTransferSession(
                    FakeWifiSoftApJoiner(),
                    JavaWebsocketTransferServer(),
                    createWifiFrameCipher(createSessionCipher(), secret, WifiPayloadCipher.ChaCha20Poly1305),
                )
            },
            bleDownload = { byteArrayOf(1) },
        ).transferOrFallback("x", "y", port = port, bindHost = "127.0.0.1", assemble = { throw failure })
        assertIs<WifiTransferCoordinator.Outcome.BleFallback>(outcome)
        outcome.reason
    }

    @Test
    fun arbitraryExceptionMessage_isNotSurfaced() {
        val reason = reasonFor(IllegalStateException("token=synthetic-secret-value"))
        assertEquals("IllegalStateException", reason)
        assertTrue(!reason.contains("synthetic-secret-value"))
    }

    @Test
    fun wifiTransferFailureCode_isSurfaced() {
        assertEquals("wifi_frame_rejected", reasonFor(WifiTransferFailure("wifi_frame_rejected")))
    }
}
