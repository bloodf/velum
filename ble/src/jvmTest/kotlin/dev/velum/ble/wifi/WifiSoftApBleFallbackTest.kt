package dev.velum.ble.wifi

import dev.velum.ble.BleTransport
import dev.velum.ble.client.PlaudDeviceClient
import dev.velum.ble.crypto.createSessionCipher
import dev.velum.ble.crypto.rsaKeyPairToPem
import dev.velum.ble.fake.FakeBleTransport
import dev.velum.ble.fake.FakePlaudPeripheral
import dev.velum.protocol.DeviceIdentityKeys
import dev.velum.protocol.Protocol20SessionSecret
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.zip.CRC32
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * #96: when the SoftAP Wi-Fi path fails mid-transfer, the coordinator falls back to a REAL BLE
 * download ([PlaudDeviceClient] against [FakePlaudPeripheral]) rather than a canned byte array.
 * The completed file must match the fixture exactly (no missing or duplicate data) and its CRC.
 * Simulated only: no real SoftAP join or hardware throughput (those stay in #8).
 */
class WifiSoftApBleFallbackTest {
    private val wifiSecret = Protocol20SessionSecret(
        key = ByteArray(32) { (it + 5).toByte() },
        nonce = ByteArray(12) { (it + 9).toByte() },
        aad = ByteArray(12) { (it + 13).toByte() },
    )
    private val preamble = WifiTransferPreamble.defaultToken("fallback-96")
    private val fixture = ByteArray(64 * 1024) { ((it * 17 + 3) and 0xFF).toByte() }
    private val wifiChunks = fixture.toList().chunked(4 * 1024).map { it.toByteArray() }

    private fun runBoundedTest(block: suspend CoroutineScope.() -> Unit) {
        runBlocking { withTimeout(20_000) { block() } }
    }

    private fun wifiCipher() = createWifiFrameCipher(createSessionCipher(), wifiSecret, WifiPayloadCipher.ChaCha20Poly1305)
    // On the address the server binds: on macOS a wildcard bind succeeds beside another process's loopback listener.
    private fun freePort(): Int = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
    private fun crc(bytes: ByteArray): Long = CRC32().also { it.update(bytes) }.value

    /** Counts frames from the simulated peripheral, which only answers real client writes: the BLE path really ran. */
    private class CountingBle(private val inner: BleTransport) : BleTransport by inner {
        var frames = 0
        override fun notifications(): Flow<ByteArray> = inner.notifications().onEach { frames++ }
    }

    private val keys = rsaKeyPairToPem().let { (pub, priv) ->
        DeviceIdentityKeys(
            serialNumber = "881FAKE1",
            serialSignature = ByteArray(256) { (it % 251).toByte() },
            publicKeyPem = pub,
            privateKeyPem = priv,
            bindToken = "synthetic-token",
        )
    }

    /** Full BLE download of the fixture through the real client: connect, unlock, list, download. */
    private fun bleDownloadOf(ble: CountingBle): suspend () -> ByteArray = {
        ble.connect("fake-note-pro")
        val client = PlaudDeviceClient(ble, createSessionCipher(), keys)
        try {
            client.unlock()
            client.handshake()
            val entry = client.listSessions().entries.single()
            client.downloadSession(entry.sessionId, entry.sizeBytes)
        } finally {
            client.close()
        }
    }

    private fun newBle(): CountingBle {
        val peripheral = FakePlaudPeripheral(
            cipher = createSessionCipher(),
            clientKeys = keys,
            sessionFile = fixture,
            requirePublicKey = false,
        )
        return CountingBle(FakeBleTransport(peripheral))
    }

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

    private suspend fun assembleExact(
        s: WifiFastTransferSession,
        total: Int,
        subscribed: CompletableDeferred<Unit>,
        consumerStallMs: Long = 0,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        @Suppress("UNCHECKED_CAST")
        val frames = s.incomingEncryptedFrames() as SharedFlow<ByteArray>
        frames.onSubscription { subscribed.complete(Unit) }
            .transformWhile<ByteArray, Unit> { sealed ->
                if (out.size() == 0) delay(consumerStallMs)
                out.write(s.openIncoming(sealed) ?: throw WifiTransferFailure("wifi_frame_rejected"))
                out.size() < total
            }.collect { }
        return out.toByteArray()
    }

    /** Sends [good] valid frames, then optionally one corrupted frame, then closes (a dropped link). */
    private fun CoroutineScope.launchDevice(
        port: Int,
        subscribed: CompletableDeferred<Unit>,
        good: Int,
        corruptNext: Boolean,
        delivered: DeliveredFrames,
    ) = launch(Dispatchers.IO) {
        val device = connectDevice(port)
        try {
            subscribed.await()
            val c = wifiCipher()
            device.sendPreamble(preamble)
            for (i in 0 until good) device.sendBinary(c.seal(i.toLong(), wifiChunks[i]))
            if (corruptNext) {
                device.sendBinary(c.seal(good.toLong(), wifiChunks[good]).also { it[0] = (it[0].toInt() xor 0x55).toByte() })
            }
            delivered.await(good + if (corruptNext) 1 else 0)
        } finally {
            device.close()
        }
    }

    private suspend fun transfer(
        wifiTimeoutMs: Long,
        good: Int,
        corruptNext: Boolean,
        consumerStallMs: Long = 0,
    ): Triple<WifiTransferCoordinator.Outcome, CountingBle, FakeWifiSoftApJoiner> = coroutineScope2 {
        val port = freePort()
        val subscribed = CompletableDeferred<Unit>()
        val ble = newBle()
        val joiner = FakeWifiSoftApJoiner()
        val server = JavaWebsocketTransferServer()
        val delivered = DeliveredFrames(server, this)
        val device = launchDevice(port, subscribed, good, corruptNext, delivered)
        val outcome = WifiTransferCoordinator(
            sessionFactory = { WifiFastTransferSession(joiner, server, wifiCipher()) },
            bleDownload = bleDownloadOf(ble),
            wifiTimeout = wifiTimeoutMs.milliseconds,
        ).transferOrFallback(
            "PLAUD-FAKE", "password12", port = port, preamble = preamble, bindHost = "127.0.0.1",
            assemble = { assembleExact(it, fixture.size, subscribed, consumerStallMs) },
        )
        device.join()
        delivered.stop()
        Triple(outcome, ble, joiner)
    }

    private suspend fun <T> coroutineScope2(block: suspend CoroutineScope.() -> T): T = kotlinx.coroutines.coroutineScope(block)

    @Test
    fun aeadFailureMidTransfer_fallsBackToRealBleDownload_withExactBytes() = runBoundedTest {
        val started = System.nanoTime()
        val (outcome, ble, joiner) = transfer(wifiTimeoutMs = 10_000, good = 2, corruptNext = true)
        assertIs<WifiTransferCoordinator.Outcome.BleFallback>(outcome)
        // Failed once on CI with an unknown reason; the time tells a timeout from an early failure.
        assertEquals("wifi_frame_rejected", outcome.reason, "after ${(System.nanoTime() - started) / 1_000_000} ms")
        assertTrue(ble.frames > 0, "BLE path must actually have run")
        assertEquals(1, joiner.joinCount, "SoftAP must have been joined before it was left")
        assertTrue(!joiner.joined, "SoftAP must be left after failure")
        assertContentEquals(fixture, outcome.bytes) // no missing or duplicate data, no partial Wi-Fi bytes
        assertEquals(crc(fixture), crc(outcome.bytes))
    }

    @Test
    fun aRejectedFrameBeatsACloseThatFollowedIt_howeverSlowTheConsumer() = runBoundedTest {
        val (outcome, _, _) = transfer(wifiTimeoutMs = 15_000, good = 2, corruptNext = true, consumerStallMs = 3_000)
        assertIs<WifiTransferCoordinator.Outcome.BleFallback>(outcome)
        assertEquals("wifi_frame_rejected", outcome.reason)
    }

    @Test
    fun aCompleteFileBeatsACloseThatFollowedIt_howeverSlowTheConsumer() = runBoundedTest {
        val (outcome, ble, _) = transfer(wifiTimeoutMs = 15_000, good = wifiChunks.size, corruptNext = false, consumerStallMs = 3_000)
        assertIs<WifiTransferCoordinator.Outcome.Wifi>(outcome)
        assertContentEquals(fixture, outcome.bytes)
        assertEquals(0, ble.frames, "BLE must not run")
    }

    /** The close lands after assemble's last frame, while it is still working on the result. */
    @Test
    fun aCloseWhileAssembleFinishes_doesNotDiscardACompleteFile() = runBoundedTest {
        val port = freePort()
        val subscribed = CompletableDeferred<Unit>()
        val assembleRead = CompletableDeferred<Unit>()
        val deviceClosed = CompletableDeferred<Unit>()
        val device = launch(Dispatchers.IO) {
            val d = connectDevice(port)
            subscribed.await()
            val c = wifiCipher()
            d.sendPreamble(preamble)
            wifiChunks.forEachIndexed { i, chunk -> d.sendBinary(c.seal(i.toLong(), chunk)) }
            assembleRead.await()
            d.close()
            deviceClosed.complete(Unit)
        }
        val outcome = WifiTransferCoordinator(
            sessionFactory = { WifiFastTransferSession(FakeWifiSoftApJoiner(), JavaWebsocketTransferServer(), wifiCipher()) },
            bleDownload = { error("BLE must not run") },
            wifiTimeout = 15_000.milliseconds,
        ).transferOrFallback(
            "PLAUD-FAKE", "password12", port = port, preamble = preamble, bindHost = "127.0.0.1",
            assemble = {
                val bytes = assembleExact(it, fixture.size, subscribed)
                assembleRead.complete(Unit)
                deviceClosed.await()
                delay(500) // the CRC check and decrypt a real assemble does here
                bytes
            },
        )
        device.join()
        assertIs<WifiTransferCoordinator.Outcome.Wifi>(outcome)
        assertContentEquals(fixture, outcome.bytes)
    }

    /** One frame held by the consumer and exactly 64 queued: a full buffer, then the close. */
    @Test
    fun aCloseWithTheBufferExactlyFull_stillComesAfterItsFrames() = runBoundedTest {
        val port = freePort()
        val frames = List(65) { i -> ByteArray(64) { i.toByte() } }
        val subscribed = CompletableDeferred<Unit>()
        val firstHeld = CompletableDeferred<Unit>()
        val deviceClosed = CompletableDeferred<Unit>()
        val device = launch(Dispatchers.IO) {
            val d = connectDevice(port)
            subscribed.await()
            val c = wifiCipher()
            d.sendPreamble(preamble)
            d.sendBinary(c.seal(0, frames[0]))
            firstHeld.await()
            for (i in 1 until frames.size) d.sendBinary(c.seal(i.toLong(), frames[i]))
            d.close()
            deviceClosed.complete(Unit)
        }
        val outcome = WifiTransferCoordinator(
            sessionFactory = { WifiFastTransferSession(FakeWifiSoftApJoiner(), JavaWebsocketTransferServer(), wifiCipher()) },
            bleDownload = { error("BLE must not run") },
            wifiTimeout = 15_000.milliseconds,
        ).transferOrFallback(
            "PLAUD-FAKE", "password12", port = port, preamble = preamble, bindHost = "127.0.0.1",
            assemble = { s ->
                val out = ByteArrayOutputStream()
                @Suppress("UNCHECKED_CAST")
                (s.incomingEncryptedFrames() as SharedFlow<ByteArray>)
                    .onSubscription { subscribed.complete(Unit) }
                    .transformWhile<ByteArray, Unit> { sealed ->
                        if (out.size() == 0) {
                            firstHeld.complete(Unit)
                            deviceClosed.await()
                            delay(500) // let the server handle the close
                        }
                        out.write(s.openIncoming(sealed) ?: throw WifiTransferFailure("wifi_frame_rejected"))
                        out.size() < 65 * 64
                    }.collect { }
                out.toByteArray()
            },
        )
        device.join()
        assertIs<WifiTransferCoordinator.Outcome.Wifi>(outcome)
        assertContentEquals(frames.reduce { a, b -> a + b }, outcome.bytes)
    }

    @Test
    fun droppedLinkMidTransfer_fallsBackToRealBleDownload_withExactBytes() = runBoundedTest {
        val (outcome, ble, joiner) = transfer(wifiTimeoutMs = 15_000, good = 2, corruptNext = false)
        assertIs<WifiTransferCoordinator.Outcome.BleFallback>(outcome)
        assertEquals(WifiTransferServer.FAILURE_PEER_CLOSED, outcome.reason)
        assertTrue(ble.frames > 0, "BLE path must actually have run")
        assertEquals(1, joiner.joinCount, "SoftAP must have been joined before it was left")
        assertTrue(!joiner.joined, "SoftAP must be left after failure")
        assertContentEquals(fixture, outcome.bytes)
        assertEquals(crc(fixture), crc(outcome.bytes))
    }
}
