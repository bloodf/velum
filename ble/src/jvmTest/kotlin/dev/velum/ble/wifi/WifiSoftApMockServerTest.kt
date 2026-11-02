package dev.velum.ble.wifi

import dev.velum.ble.crypto.createSessionCipher
import dev.velum.protocol.Protocol20SessionSecret
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.util.zip.CRC32
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.milliseconds

/**
 * Simulated SoftAP :8081 coverage for #96: a real [JavaWebsocketTransferServer] on an
 * ephemeral loopback port plus [FakeWifiDeviceClient] as the device. No real SoftAP join,
 * network binding or hardware throughput is exercised here; those stay in #8.
 * Nonce behaviour is tracked in #28 (see [wifiFrameSealing_isDeterministicPerSecretAndSeq]).
 */
class WifiSoftApMockServerTest {
    private val secret = Protocol20SessionSecret(
        key = ByteArray(32) { (it + 3).toByte() },
        nonce = ByteArray(12) { (it + 7).toByte() },
        aad = ByteArray(12) { (it + 11).toByte() },
    )
    private val preamble = WifiTransferPreamble.defaultToken("mock-96")
    private val fixture = ByteArray(16 * CHUNK) { ((it * 31 + 7) and 0xFF).toByte() }

    private fun cipher(mode: WifiPayloadCipher) = createWifiFrameCipher(createSessionCipher(), secret, mode)

    /** Bounds every test so a lost frame fails fast instead of hanging for the 45 s wifiTimeout. */
    private fun runTest20(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) {
        runBlocking { withTimeout(20_000) { block() } }
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun crc(bytes: ByteArray): Long = CRC32().also { it.update(bytes) }.value

    /** Connect-retry (no fixed sleeps): the server may not be bound yet. */
    private suspend fun connectDevice(port: Int): FakeWifiDeviceClient {
        val deadline = System.nanoTime() + 5_000_000_000L
        var last: Throwable? = null
        while (System.nanoTime() < deadline) {
            val device = FakeWifiDeviceClient(port = port)
            try {
                withContext(Dispatchers.IO) { device.connect(timeoutMs = 500) }
                return device
            } catch (t: Throwable) {
                last = t
                device.close()
                delay(20)
            }
        }
        error("device could not connect on :$port: ${last?.message}")
    }

    private fun session(
        mode: WifiPayloadCipher,
        server: WifiTransferServer = JavaWebsocketTransferServer(),
        joiner: FakeWifiSoftApJoiner = FakeWifiSoftApJoiner(),
    ) = WifiFastTransferSession(joiner, server, cipher(mode))

    /** Opens frames in order until [total] bytes are assembled; any open failure aborts the Wi-Fi path. */
    private suspend fun assembleExact(
        s: WifiFastTransferSession,
        total: Int,
        subscribed: CompletableDeferred<Unit>,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        @Suppress("UNCHECKED_CAST")
        val frames = s.server.incomingEncryptedFrames() as SharedFlow<ByteArray>
        frames.onSubscription { subscribed.complete(Unit) }
            .transformWhile<ByteArray, Unit> { sealed ->
                val plain = s.openIncoming(sealed) ?: error("wifi_frame_rejected")
                out.write(plain)
                out.size() < total
            }.collect { }
        return out.toByteArray()
    }

    private suspend fun sendChunks(device: FakeWifiDeviceClient, c: WifiFrameCipher, chunks: List<ByteArray>) {
        withContext(Dispatchers.IO) {
            device.sendPreamble(preamble)
            chunks.forEachIndexed { i, chunk -> device.sendBinary(c.seal(i.toLong(), chunk)) }
        }
    }

    @Test
    fun completeTransfer_matchesFixtureAndCrc_bothCiphers() = runTest20 {
        for (mode in WifiPayloadCipher.entries) {
            val port = freePort()
            val joiner = FakeWifiSoftApJoiner()
            val s = session(mode, joiner = joiner)
            val subscribed = CompletableDeferred<Unit>()
            val device = launchDevice(port, subscribed, mode, fixture.toList().chunked(CHUNK).map { it.toByteArray() })
            val started = System.nanoTime()
            val outcome = WifiTransferCoordinator({ s }, { error("BLE must not run") }).transferOrFallback(
                ssid = "PLAUD-FAKE", password = "password12", port = port,
                preamble = preamble, bindHost = "127.0.0.1",
                assemble = { assembleExact(it, fixture.size, subscribed) },
            )
            val seconds = (System.nanoTime() - started) / 1e9
            device.join()
            assertIs<WifiTransferCoordinator.Outcome.Wifi>(outcome)
            assertTrue(!joiner.joined, "$mode SoftAP must be left after success")
            assertContentEquals(fixture, outcome.bytes)
            assertEquals(crc(fixture), crc(outcome.bytes))
            // Loopback JVM figure only; not a hardware throughput claim (#8).
            println("softap-mock $mode: ${fixture.size} bytes in ${"%.3f".format(seconds)}s " +
                "(${"%.2f".format(fixture.size / 1e6 / seconds)} MB/s, loopback)")
        }
    }

    private fun kotlinx.coroutines.CoroutineScope.launchDevice(
        port: Int,
        subscribed: CompletableDeferred<Unit>,
        mode: WifiPayloadCipher,
        chunks: List<ByteArray>,
    ) = launch(Dispatchers.IO) {
        val device = connectDevice(port)
        try {
            subscribed.await() // SharedFlow has no replay; send only once the collector is attached.
            sendChunks(device, cipher(mode), chunks)
        } finally {
            device.close()
        }
    }

    /**
     * Sentinel proof: after [sendBad] (on its own connection, then closed), a valid device on a
     * second connection sends one frame. Only that frame may reach the session, so the bad input
     * was really dropped (not merely missed within a timeout).
     */
    private suspend fun assertBadInputDropped(mode: WifiPayloadCipher, sendBad: (FakeWifiDeviceClient) -> Unit): Unit = coroutineScope {
        val port = freePort()
        val s = session(mode)
        s.start("PLAUD-FAKE", "password12", port, preamble = preamble, bindHost = "127.0.0.1")
        try {
            @Suppress("UNCHECKED_CAST")
            val frames = s.server.incomingEncryptedFrames() as SharedFlow<ByteArray>
            val subscribed = CompletableDeferred<Unit>()
            val first = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(5_000) { frames.onSubscription { subscribed.complete(Unit) }.first() }
            }
            subscribed.await()
            val bad = connectDevice(port)
            withContext(Dispatchers.IO) {
                runCatching { sendBad(bad) }
                bad.close() // finish the bad connection before the sentinel so its close cannot reset the sentinel's preamble state
            }
            val sentinel = connectDevice(port)
            withContext(Dispatchers.IO) {
                sentinel.sendPreamble(preamble)
                sentinel.sendBinary(cipher(mode).seal(0, byteArrayOf(9)))
            }
            assertContentEquals(byteArrayOf(9), s.openIncoming(first.await()), "only the sentinel frame may arrive")
            sentinel.close()
        } finally {
            s.stop()
        }
    }

    @Test
    fun preambleMismatch_dropsFramesAndConnection() = runTest20 {
        assertBadInputDropped(WifiPayloadCipher.ChaCha20Poly1305) { d ->
            d.sendPreamble(WifiTransferPreamble.defaultToken("wrong"))
            d.sendBinary(cipher(WifiPayloadCipher.ChaCha20Poly1305).seal(0, byteArrayOf(1)))
        }
    }

    @Test
    fun frameBeforePreamble_isTreatedAsPreambleAndRejected() = runTest20 {
        assertBadInputDropped(WifiPayloadCipher.AesGcm) { d ->
            d.sendBinary(cipher(WifiPayloadCipher.AesGcm).seal(0, byteArrayOf(1)))
        }
    }

    /** Receives [count] raw sealed frames the device sends after a valid preamble. */
    private suspend fun receiveRaw(
        port: Int,
        mode: WifiPayloadCipher,
        count: Int,
        send: suspend (FakeWifiDeviceClient, WifiFrameCipher) -> Unit,
    ): Pair<WifiFastTransferSession, List<ByteArray>> = coroutineScope {
        val s = session(mode)
        s.start("PLAUD-FAKE", "password12", port, preamble = preamble, bindHost = "127.0.0.1")
        val subscribed = CompletableDeferred<Unit>()
        val frames = s.server.incomingEncryptedFrames() as SharedFlow<ByteArray>
        val collected = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(5_000) {
                val list = mutableListOf<ByteArray>()
                frames.onSubscription { subscribed.complete(Unit) }
                    .transformWhile<ByteArray, Unit> { list += it; list.size < count }
                    .collect { }
                list
            }
        }
        subscribed.await()
        val device = connectDevice(port)
        try {
            withContext(Dispatchers.IO) {
                device.sendPreamble(preamble)
                send(device, cipher(mode))
            }
            s to collected.await()
        } finally {
            device.close()
        }
    }

    @Test
    fun seqOrdering_outOfOrderAndReplay_areRejected() = runTest20 {
        for (mode in WifiPayloadCipher.entries) {
            val port = freePort()
            val (s, raw) = receiveRaw(port, mode, 3) { d, c ->
                d.sendBinary(c.seal(0, byteArrayOf(10)))
                d.sendBinary(c.seal(0, byteArrayOf(10))) // replay of seq 0
                d.sendBinary(c.seal(2, byteArrayOf(30))) // gap: seq 1 skipped
            }
            try {
                assertContentEquals(byteArrayOf(10), s.openIncoming(raw[0]))
                assertNull(s.openIncoming(raw[1]), "$mode replayed seq 0 must be rejected")
                assertNull(s.openIncoming(raw[2]), "$mode out-of-order seq 2 must be rejected")
            } finally {
                s.stop()
            }
        }
    }

    @Test
    fun aeadFailure_tamperedFrameRejected() = runTest20 {
        for (mode in WifiPayloadCipher.entries) {
            val port = freePort()
            val (s, raw) = receiveRaw(port, mode, 1) { d, c ->
                val sealed = c.seal(0, byteArrayOf(1, 2, 3, 4))
                sealed[sealed.size - 1] = (sealed.last().toInt() xor 0x01).toByte() // corrupt the tag
                d.sendBinary(sealed)
            }
            try {
                assertNull(s.openIncoming(raw[0]), "$mode tampered frame must fail AEAD")
            } finally {
                s.stop()
            }
        }
    }

    @Test
    fun aeadFailure_midTransfer_fallsBackToBleWithExactBytes() = runTest20 {
        val port = freePort()
        val subscribed = CompletableDeferred<Unit>()
        val chunks = fixture.toList().chunked(CHUNK).map { it.toByteArray() }
        val device = launch(Dispatchers.IO) {
            val d = connectDevice(port)
            try {
                subscribed.await()
                val c = cipher(WifiPayloadCipher.ChaCha20Poly1305)
                d.sendPreamble(preamble)
                for (i in 0 until 3) d.sendBinary(c.seal(i.toLong(), chunks[i]))
                d.sendBinary(c.seal(3, chunks[3]).also { it[0] = (it[0].toInt() xor 0x55).toByte() })
            } finally {
                d.close()
            }
        }
        val joiner = FakeWifiSoftApJoiner()
        val outcome = WifiTransferCoordinator({ session(WifiPayloadCipher.ChaCha20Poly1305, joiner = joiner) }, { fixture })
            .transferOrFallback(
                "PLAUD-FAKE", "password12", port = port, preamble = preamble, bindHost = "127.0.0.1",
                assemble = { assembleExact(it, fixture.size, subscribed) },
            )
        device.join()
        assertIs<WifiTransferCoordinator.Outcome.BleFallback>(outcome)
        assertEquals("wifi_frame_rejected", outcome.reason)
        assertTrue(!joiner.joined, "SoftAP must be left after failure")
        assertEquals(crc(fixture), crc(outcome.bytes))
        assertContentEquals(fixture, outcome.bytes) // BLE result is whole: no partial Wi-Fi bytes mixed in
    }

    @Test
    fun disconnectMidTransfer_timesOutAndFallsBackToBle_thenReleasesPort() = runTest20 {
        val port = freePort()
        val server = JavaWebsocketTransferServer()
        val subscribed = CompletableDeferred<Unit>()
        val joiner = FakeWifiSoftApJoiner()
        val half = fixture.toList().chunked(CHUNK).map { it.toByteArray() }.take(8)
        val device = launchDevice(port, subscribed, WifiPayloadCipher.ChaCha20Poly1305, half) // then closes
        val outcome = WifiTransferCoordinator(
            sessionFactory = { session(WifiPayloadCipher.ChaCha20Poly1305, server, joiner) },
            bleDownload = { fixture },
            wifiTimeout = 1_500.milliseconds,
        ).transferOrFallback(
            "PLAUD-FAKE", "password12", port = port, preamble = preamble, bindHost = "127.0.0.1",
            assemble = { assembleExact(it, fixture.size, subscribed) },
        )
        device.join()
        assertIs<WifiTransferCoordinator.Outcome.BleFallback>(outcome)
        assertTrue(outcome.reason.startsWith("wifi_timeout"), outcome.reason)
        assertTrue(!joiner.joined, "SoftAP must be left after failure")
        assertEquals(crc(fixture), crc(outcome.bytes))
        assertPortReleased(port)
    }

    @Test
    fun deviceNeverJoins_timeoutFallsBackToBle() = runTest20 {
        val port = freePort()
        val joiner = FakeWifiSoftApJoiner()
        val outcome = WifiTransferCoordinator(
            sessionFactory = { session(WifiPayloadCipher.AesGcm, joiner = joiner) },
            bleDownload = { fixture },
            wifiTimeout = 300.milliseconds,
        ).transferOrFallback(
            "PLAUD-FAKE", "password12", port = port, preamble = preamble, bindHost = "127.0.0.1",
            assemble = { assembleExact(it, fixture.size, CompletableDeferred()) },
        )
        assertIs<WifiTransferCoordinator.Outcome.BleFallback>(outcome)
        assertTrue(outcome.reason.startsWith("wifi_timeout"), outcome.reason)
        assertTrue(!joiner.joined, "SoftAP must be left after failure")
        assertContentEquals(fixture, outcome.bytes)
        assertPortReleased(port)
    }

    @Test
    fun serverStop_releasesPort_afterSuccess() = runTest20 {
        val port = freePort()
        val s = session(WifiPayloadCipher.ChaCha20Poly1305)
        s.start("PLAUD-FAKE", "password12", port, preamble = preamble, bindHost = "127.0.0.1")
        val device = connectDevice(port)
        device.close()
        s.stop()
        assertPortReleased(port)
    }

    /** Binding succeeds only once the listener is gone; retried because close is asynchronous. */
    private suspend fun assertPortReleased(port: Int) {
        val deadline = System.nanoTime() + 5_000_000_000L
        var last: Throwable? = null
        while (System.nanoTime() < deadline) {
            try {
                ServerSocket(port).close()
                return
            } catch (t: Throwable) {
                last = t
                delay(20)
            }
        }
        error("port $port still bound after stop: ${last?.message}")
    }

    /** Characterization (#28 tracks nonce policy): sealing is a pure function of secret, seq and payload. */
    @Test
    fun wifiFrameSealing_isDeterministicPerSecretAndSeq() {
        for (mode in WifiPayloadCipher.entries) {
            val plain = ByteArray(48) { it.toByte() }
            val sealed = cipher(mode).seal(0, plain)
            assertContentEquals(sealed, cipher(mode).seal(0, plain), "$mode same secret+seq => same ciphertext")
            assertContentEquals(plain, cipher(mode).open(0, sealed)?.second, "$mode roundtrip")
        }
    }

    private companion object {
        const val CHUNK = 16 * 1024
    }
}
