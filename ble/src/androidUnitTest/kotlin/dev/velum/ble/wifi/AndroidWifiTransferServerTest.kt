package dev.velum.ble.wifi

import android.net.Network
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.velum.ble.crypto.createSessionCipher
import dev.velum.protocol.Protocol20SessionSecret
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowNetwork

/**
 * #96 Android-side mock-server coverage: the real [AndroidWifiTransferServer] on an ephemeral
 * loopback port, and once on the literal :8081, with a java-websocket client as the device and a recording [Network] standing in
 * for the SoftAP network. No Wi-Fi, SoftAP join or hardware is involved; those stay in #8.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AndroidWifiTransferServerTest {
    private val secret = Protocol20SessionSecret(
        key = ByteArray(32) { (it + 3).toByte() },
        nonce = ByteArray(12) { (it + 7).toByte() },
        aad = ByteArray(12) { (it + 11).toByte() },
    )
    private val preamble = WifiTransferPreamble.defaultToken("mock-96-android")
    private val fixture = ByteArray(16 * CHUNK) { ((it * 31 + 7) and 0xFF).toByte() }

    private fun cipher(mode: WifiPayloadCipher) = createWifiFrameCipher(createSessionCipher(), secret, mode)

    /** Generous overall bound: a lost frame fails, but a loaded machine does not. */
    private fun runTest(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) {
        runBlocking { withTimeout(90_000) { block() } }
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun crc(bytes: ByteArray): Long = CRC32().also { it.update(bytes) }.value

    /** Robolectric's shadow Network counts sockets passed to bindSocket (the fake SoftAP network). */
    private class RecordingNetwork {
        val network: Network = ShadowNetwork.newInstance(7)
        val boundCount: Int get() = (Shadow.extract(network) as ShadowNetwork).boundSocketCount()
    }

    private class RecordingJoiner : WifiSoftApJoiner {
        @Volatile var joined = false
        var joinCalls = 0
        var leaveCalls = 0
        override suspend fun join(ssid: String, password: String, ssidIsPattern: Boolean) { joined = true; joinCalls++ }
        override suspend fun leave() { joined = false; leaveCalls++ }
    }

    private class Device(port: Int) {
        private val closed = CountDownLatch(1)
        @Volatile var closeCode = -1
        @Volatile var closeReason = ""
        val client = object : WebSocketClient(URI("ws://127.0.0.1:$port")) {
            override fun onOpen(handshakedata: ServerHandshake?) = Unit
            override fun onMessage(message: String?) = Unit
            override fun onMessage(bytes: ByteBuffer?) = Unit
            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                closeCode = code; closeReason = reason.orEmpty(); closed.countDown()
            }
            override fun onError(ex: Exception?) = Unit
        }

        /** Per-attempt timeout is generous: an abandoned attempt can still be accepted late. */
        fun connect() = check(client.connectBlocking(15, TimeUnit.SECONDS)) { "device connect failed" }
        fun send(bytes: ByteArray) = client.send(bytes)
        fun awaitClosed(): Boolean = closed.await(15, TimeUnit.SECONDS)
        fun close() = runCatching { client.closeBlocking() }
    }

    private fun session(
        mode: WifiPayloadCipher,
        server: WifiTransferServer,
        joiner: RecordingJoiner = RecordingJoiner(),
    ) = WifiFastTransferSession(joiner, server, cipher(mode))

    /** Connect-retry (no fixed sleeps): the server may not be bound yet, so a refused attempt is retried. */
    private suspend fun connectDevice(port: Int): Device {
        val deadline = System.nanoTime() + 30_000_000_000L
        var last: Throwable? = null
        while (System.nanoTime() < deadline) {
            val d = Device(port)
            try {
                withContext(Dispatchers.IO) { d.connect() }
                return d
            } catch (t: Throwable) {
                last = t
                d.close()
                delay(20)
            }
        }
        error("device could not connect on :$port: ${last?.message}")
    }

    private suspend fun assertPortReleased(port: Int) {
        val deadline = System.nanoTime() + 15_000_000_000L
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

    /** Opens frames in order until [total] bytes are assembled; any open failure aborts the Wi-Fi path. */
    private suspend fun assembleExact(
        s: WifiFastTransferSession,
        total: Int,
        subscribed: CompletableDeferred<Unit>,
        out: ByteArrayOutputStream = ByteArrayOutputStream(),
        consumerStallMs: Long = 0,
    ): ByteArray {
        @Suppress("UNCHECKED_CAST")
        val frames = s.server.incomingEncryptedFrames() as SharedFlow<ByteArray>
        frames.onSubscription { subscribed.complete(Unit) }
            .transformWhile<ByteArray, Unit> { sealed ->
                if (out.size() == 0) delay(consumerStallMs)
                out.write(s.openIncoming(sealed) ?: throw WifiTransferFailure("wifi_frame_rejected"))
                out.size() < total
            }.collect { }
        return out.toByteArray()
    }

    private fun kotlinx.coroutines.CoroutineScope.launchDevice(
        port: Int,
        subscribed: CompletableDeferred<Unit>,
        mode: WifiPayloadCipher,
        chunks: List<ByteArray>,
        tolerateFailure: Boolean = false,
    ) = launch(Dispatchers.IO) {
        try {
            val d = connectDevice(port)
            try {
                subscribed.await() // SharedFlow has no replay; send only once the collector is attached.
                d.send(preamble)
                val c = cipher(mode)
                chunks.forEachIndexed { i, chunk -> d.send(c.seal(i.toLong(), chunk)) }
            } finally {
                d.close()
            }
        } catch (t: Exception) {
            if (!tolerateFailure) throw t
        }
    }

    private val chunks get() = fixture.toList().chunked(CHUNK).map { it.toByteArray() }

    @Test
    fun callsBindSocketOncePerAcceptedConnection() = runTest {
        val port = freePort()
        val network = RecordingNetwork()
        val server = AndroidWifiTransferServer(softApNetwork = { network.network })
        server.start(port, "127.0.0.1", preamble)
        try {
            val d = connectDevice(port)
            try {
                withTimeout(15_000) { while (network.boundCount == 0) delay(10) }
                assertEquals(1, network.boundCount, "exactly the accepted client socket is bound")
                val second = connectDevice(port)
                try {
                    withTimeout(15_000) { while (network.boundCount < 2) delay(10) }
                    assertEquals(2, network.boundCount, "one bind per accepted connection")
                } finally {
                    second.close()
                }
            } finally {
                d.close()
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun noNetwork_noBinding_serverStillDeliversFrames() = runTest {
        val port = freePort()
        val failures = mutableListOf<Throwable>()
        val server = AndroidWifiTransferServer(onBindFailure = { failures += it })
        server.start(port, "127.0.0.1", preamble)
        try {
            @Suppress("UNCHECKED_CAST")
            val frames = server.incomingEncryptedFrames() as SharedFlow<ByteArray>
            val subscribed = CompletableDeferred<Unit>()
            val first = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(30_000) { frames.onSubscription { subscribed.complete(Unit) }.first() }
            }
            subscribed.await()
            val d = connectDevice(port)
            withContext(Dispatchers.IO) { d.send(preamble); d.send(byteArrayOf(5)) }
            assertContentEquals(byteArrayOf(5), first.await())
            d.close()
            assertTrue(failures.isEmpty(), "no network, so nothing to bind or report")
        } finally {
            server.stop()
        }
    }

    @Test
    fun preambleMismatch_closesWith1008_andFrameIsDropped() = runTest {
        for (mode in WifiPayloadCipher.entries) {
            val port = freePort()
            val server = AndroidWifiTransferServer()
            server.start(port, "127.0.0.1", preamble)
            try {
                @Suppress("UNCHECKED_CAST")
                val frames = server.incomingEncryptedFrames() as SharedFlow<ByteArray>
                val subscribed = CompletableDeferred<Unit>()
                val first = async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(15_000) { frames.onSubscription { subscribed.complete(Unit) }.first() }
                }
                subscribed.await()
                val bad = connectDevice(port)
                withContext(Dispatchers.IO) {
                    bad.send(WifiTransferPreamble.defaultToken("wrong"))
                    assertTrue(bad.awaitClosed(), "$mode server must close a wrong-preamble client")
                }
                assertEquals(1008, bad.closeCode)
                assertEquals("preamble_mismatch", bad.closeReason)
                // Sentinel: only a valid second device's frame may reach the session.
                val good = connectDevice(port)
                withContext(Dispatchers.IO) {
                    good.send(preamble)
                    good.send(cipher(mode).seal(0, byteArrayOf(9)))
                }
                val s = session(mode, server)
                assertContentEquals(byteArrayOf(9), s.openIncoming(first.await()), "$mode only the sentinel frame may arrive")
                good.close()
            } finally {
                server.stop()
            }
        }
    }

    @Test
    fun strayClientConnectingAndClosing_doesNotRevokeTheAuthenticatedDevice() = runTest {
        val port = freePort()
        val server = AndroidWifiTransferServer()
        server.start(port, "127.0.0.1", preamble)
        try {
            @Suppress("UNCHECKED_CAST")
            val frames = server.incomingEncryptedFrames() as SharedFlow<ByteArray>
            val received = java.util.concurrent.ConcurrentLinkedQueue<Byte>()
            val subscribed = CompletableDeferred<Unit>()
            val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                frames.onSubscription { subscribed.complete(Unit) }.collect { received += it[0] }
            }
            subscribed.await()
            val device = connectDevice(port)
            withContext(Dispatchers.IO) { device.send(preamble) }
            val stray = connectDevice(port)
            withContext(Dispatchers.IO) { stray.close() }
            // Keep sending across the window in which the server observes the stray close; none may be lost.
            repeat(20) { i ->
                withContext(Dispatchers.IO) { device.send(byteArrayOf(i.toByte())) }
                delay(50)
            }
            withTimeout(30_000) { while (received.size < 20) delay(10) }
            assertEquals((0 until 20).map { it.toByte() }, received.toList())
            collector.cancel()
            device.close()
        } finally {
            server.stop()
        }
    }

    private suspend fun kotlinx.coroutines.CoroutineScope.completeTransferOn(port: Int, mode: WifiPayloadCipher) {
        val network = RecordingNetwork()
        val joiner = RecordingJoiner()
        val server = AndroidWifiTransferServer(softApNetwork = { network.network })
        val s = session(mode, server, joiner)
        val subscribed = CompletableDeferred<Unit>()
        val device = launchDevice(port, subscribed, mode, chunks)
        val started = System.nanoTime()
        val outcome = WifiTransferCoordinator({ s }, { error("BLE must not run") }).transferOrFallback(
            ssid = "PLAUD-FAKE", password = "password12", port = port,
            preamble = preamble, bindHost = "127.0.0.1",
            assemble = { assembleExact(it, fixture.size, subscribed) },
        )
        val seconds = (System.nanoTime() - started) / 1e9
        device.join()
        assertIs<WifiTransferCoordinator.Outcome.Wifi>(outcome)
        assertContentEquals(fixture, outcome.bytes)
        assertEquals(crc(fixture), crc(outcome.bytes))
        assertEquals(1, joiner.joinCalls); assertTrue(!joiner.joined, "$mode SoftAP left after success")
        assertTrue(network.boundCount > 0, "$mode transfer socket was bound to the network")
        assertPortReleased(port)
        // Robolectric loopback figure only; not a hardware throughput claim (#8).
        println("android-softap-mock $mode on :$port: ${fixture.size} bytes in ${"%.3f".format(seconds)}s " +
            "(${"%.2f".format(fixture.size / 1e6 / seconds)} MB/s, loopback, simulated)")
    }

    @Test
    fun completeTransfer_matchesFixtureAndCrc_andReleasesEverything() = runTest {
        for (mode in WifiPayloadCipher.entries) completeTransferOn(freePort(), mode)
    }

    /** The port a real device uses. Skips, visibly, if something else on this machine holds it. */
    @Test
    fun completeTransfer_onTheLiteralPort8081() = runTest {
        assertEquals(8081, WifiTransferServer.DEFAULT_PORT)
        val free = runCatching { ServerSocket(8081, 1, InetAddress.getByName("127.0.0.1")).close() }.isSuccess
        if (!free) println("SKIPPED completeTransfer_onTheLiteralPort8081: :8081 is in use on this machine")
        assumeTrue(":8081 is in use on this machine", free)
        for (mode in WifiPayloadCipher.entries) completeTransferOn(8081, mode)
    }

    @Test
    fun aCompleteFileBeatsACloseThatFollowedIt_howeverSlowTheConsumer() = runTest {
        val port = freePort()
        val subscribed = CompletableDeferred<Unit>()
        val s = session(WifiPayloadCipher.ChaCha20Poly1305, AndroidWifiTransferServer(), RecordingJoiner())
        val device = launchDevice(port, subscribed, WifiPayloadCipher.ChaCha20Poly1305, chunks) // then closes
        val outcome = WifiTransferCoordinator({ s }, { error("BLE must not run") }).transferOrFallback(
            ssid = "PLAUD-FAKE", password = "password12", port = port,
            preamble = preamble, bindHost = "127.0.0.1",
            assemble = { assembleExact(it, fixture.size, subscribed, consumerStallMs = 1_000) },
        )
        device.join()
        assertIs<WifiTransferCoordinator.Outcome.Wifi>(outcome)
        assertContentEquals(fixture, outcome.bytes)
    }

    @Test
    fun wifiDropsMidTransfer_fallsBackToBleWithExactBytes_andReleasesEverything() = runTest {
        val port = freePort()
        val network = RecordingNetwork()
        val joiner = RecordingJoiner()
        val server = AndroidWifiTransferServer(softApNetwork = { network.network })
        val subscribed = CompletableDeferred<Unit>()
        val arrived = ByteArrayOutputStream()
        // The device may fail to connect or send if the timeout wins the race and stops the server; the
        // `arrived` assertion below is what proves the drop happened mid-transfer.
        val device = launchDevice(port, subscribed, WifiPayloadCipher.ChaCha20Poly1305, chunks.take(8), tolerateFailure = true) // then closes
        val outcome = WifiTransferCoordinator(
            sessionFactory = { session(WifiPayloadCipher.ChaCha20Poly1305, server, joiner) },
            bleDownload = { fixture },
            wifiTimeout = 10_000.milliseconds,
        ).transferOrFallback(
            "PLAUD-FAKE", "password12", port = port, preamble = preamble, bindHost = "127.0.0.1",
            assemble = { assembleExact(it, fixture.size, subscribed, arrived) },
        )
        subscribed.complete(Unit) // never leave the device waiting if the timeout fired before subscription
        device.join()
        assertContentEquals(fixture.copyOf(8 * CHUNK), arrived.toByteArray(), "the 8 frames sent before the drop arrived intact")
        assertIs<WifiTransferCoordinator.Outcome.BleFallback>(outcome)
        assertEquals(WifiTransferServer.FAILURE_PEER_CLOSED, outcome.reason, "a peer close falls back at once, not after the timeout")
        assertContentEquals(fixture, outcome.bytes) // BLE result is whole: no partial Wi-Fi bytes mixed in
        assertEquals(crc(fixture), crc(outcome.bytes))
        assertTrue(!joiner.joined && joiner.leaveCalls >= 1, "SoftAP left after failure")
        assertPortReleased(port)
    }

    @Test
    fun aeadFailure_midTransfer_fallsBackToBle_andReleasesEverything() = runTest {
        val port = freePort()
        val joiner = RecordingJoiner()
        val server = AndroidWifiTransferServer(softApNetwork = { RecordingNetwork().network })
        val subscribed = CompletableDeferred<Unit>()
        val device = launch(Dispatchers.IO) {
            val d = connectDevice(port)
            try {
                subscribed.await()
                val c = cipher(WifiPayloadCipher.ChaCha20Poly1305)
                d.send(preamble)
                for (i in 0 until 3) d.send(c.seal(i.toLong(), chunks[i]))
                d.send(c.seal(3, chunks[3]).also { it[0] = (it[0].toInt() xor 0x55).toByte() })
            } finally {
                d.close()
            }
        }
        val outcome = WifiTransferCoordinator({ session(WifiPayloadCipher.ChaCha20Poly1305, server, joiner) }, { fixture })
            .transferOrFallback(
                "PLAUD-FAKE", "password12", port = port, preamble = preamble, bindHost = "127.0.0.1",
                assemble = { assembleExact(it, fixture.size, subscribed) },
            )
        device.join()
        assertIs<WifiTransferCoordinator.Outcome.BleFallback>(outcome)
        assertEquals("wifi_frame_rejected", outcome.reason)
        assertContentEquals(fixture, outcome.bytes)
        assertTrue(!joiner.joined, "SoftAP left after failure")
        assertPortReleased(port)
    }

    @Test
    fun slowConsumer_overflowsTheBuffer_failsThePathAndFallsBackToBle() = runTest {
        val port = freePort()
        val joiner = RecordingJoiner()
        val server = AndroidWifiTransferServer()
        val subscribed = CompletableDeferred<Unit>()
        val many = List(400) { byteArrayOf(it.toByte()) }
        val device = launchDevice(port, subscribed, WifiPayloadCipher.ChaCha20Poly1305, many, tolerateFailure = true)
        val outcome = WifiTransferCoordinator(
            sessionFactory = { session(WifiPayloadCipher.ChaCha20Poly1305, server, joiner) },
            bleDownload = { fixture },
            wifiTimeout = 60_000.milliseconds,
        ).transferOrFallback(
            "PLAUD-FAKE", "password12", port = port, preamble = preamble, bindHost = "127.0.0.1",
            assemble = { s ->
                @Suppress("UNCHECKED_CAST")
                (s.server.incomingEncryptedFrames() as SharedFlow<ByteArray>)
                    .onSubscription { subscribed.complete(Unit) }
                    .collect { delay(60_000) } // stuck on the first frame, so the 64-frame buffer fills
                ByteArray(0)
            },
        )
        subscribed.complete(Unit)
        device.join()
        assertIs<WifiTransferCoordinator.Outcome.BleFallback>(outcome)
        assertEquals(WifiTransferServer.FAILURE_FRAME_OVERFLOW, outcome.reason)
        assertContentEquals(fixture, outcome.bytes)
        assertTrue(!joiner.joined)
        assertPortReleased(port)
    }

    @Test
    fun deviceNeverJoins_timesOutToBle_andReleasesPort() = runTest {
        val port = freePort()
        val joiner = RecordingJoiner()
        val server = AndroidWifiTransferServer(softApNetwork = { RecordingNetwork().network })
        val outcome = WifiTransferCoordinator(
            sessionFactory = { session(WifiPayloadCipher.AesGcm, server, joiner) },
            bleDownload = { fixture },
            wifiTimeout = 500.milliseconds,
        ).transferOrFallback(
            "PLAUD-FAKE", "password12", port = port, preamble = preamble, bindHost = "127.0.0.1",
            assemble = { assembleExact(it, fixture.size, CompletableDeferred()) },
        )
        assertIs<WifiTransferCoordinator.Outcome.BleFallback>(outcome)
        assertContentEquals(fixture, outcome.bytes)
        assertTrue(!joiner.joined)
        assertPortReleased(port)
    }

    private companion object {
        const val CHUNK = 16 * 1024
    }

    @Test
    fun portInUse_failsStart() = runTest {
        ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { occupied ->
            val started = System.nanoTime()
            val failure = assertFailsWith<WifiTransferFailure> {
                AndroidWifiTransferServer().start(occupied.localPort, "127.0.0.1", WifiTransferPreamble.defaultToken("bind-219"))
            }
            assertEquals(AndroidWifiTransferServer.BIND_FAILURE_CODE, failure.code)
            // 5 s is the server's limit for a wait that went wrong: the listener's own error must end it sooner.
            assertTrue(System.nanoTime() - started < 5_000_000_000L, "the bind failure must be noticed, not waited out")
        }
    }
}
