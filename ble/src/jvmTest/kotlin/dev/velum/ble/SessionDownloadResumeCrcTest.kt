package dev.velum.ble

import dev.velum.ble.client.FileSessionDownloadProgressStore
import dev.velum.ble.client.FileSessionPartialFileFactory
import dev.velum.ble.client.InMemorySessionDownloadProgressStore
import dev.velum.ble.client.PlaudDeviceClient
import dev.velum.ble.client.SessionCrcMismatchException
import dev.velum.ble.client.SessionDownloadEngine
import dev.velum.ble.client.SessionDownloadStatus
import dev.velum.ble.client.SessionDownloadTruncatedException
import dev.velum.ble.client.SyntheticSessionContent
import dev.velum.ble.client.downloadInto
import dev.velum.ble.crypto.createSessionCipher
import dev.velum.ble.crypto.rsaKeyPairToPem
import dev.velum.ble.fake.DownloadFault
import dev.velum.ble.fake.FakeBleTransport
import dev.velum.ble.fake.FakePlaudPeripheral
import dev.velum.ble.wifi.UnsupportedWifiSoftApJoiner
import dev.velum.ble.wifi.WifiFastTransferSession
import dev.velum.ble.wifi.WifiPayloadCipher
import dev.velum.ble.wifi.WifiTransferCoordinator
import dev.velum.ble.wifi.createWifiFrameCipher
import dev.velum.ble.wifi.JavaWebsocketTransferServer
import dev.velum.protocol.Crc16Ccitt
import dev.velum.protocol.DeviceIdentityKeys
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files

/**
 * Simulated-fault coverage for multi-hour resume + CRC (#94).
 * Synthetic data only — never talks to real hardware.
 */
class SessionDownloadResumeCrcTest {
    @Test
    fun multiHour_synthetic_streamsAndMatchesCrc() = runBlocking {
        val size = SyntheticSessionContent.TWO_HOUR_BYTES
        val content = SyntheticSessionContent(size)
        val expectedCrc = streamCrc(content)
        val dir = tempDir()
        val store = FileSessionDownloadProgressStore(File(dir, "ckpt"))
        val files = FileSessionPartialFileFactory(File(dir, "partials"))
        val engine = SessionDownloadEngine(store, files)

        // Stream without BLE crypto so a 2h-sized payload stays practical in CI;
        // BLE fault paths are covered by the smaller peripheral tests below.
        val success = engine.download(sessionId = 42L, sizeBytes = size) { sink ->
            var offset = sink.startOffset
            var crc = sink.initialCrc
            val buf = ByteArray(64 * 1024)
            while (offset < size) {
                val n = minOf(buf.size.toLong(), size - offset).toInt()
                content.copyInto(offset, n, buf, 0)
                val chunk = if (n == buf.size) buf.copyOf() else buf.copyOf(n)
                sink.sink.write(chunk)
                offset += n
                crc = Crc16Ccitt.update(crc, chunk)
                sink.onVerified(offset, crc)
            }
            dev.velum.ble.client.SessionDownloadReceipt(
                sessionId = 42L,
                deviceCrc = expectedCrc,
                verifiedOffset = offset,
            )
        }

        assertEquals(size, success.sizeBytes)
        assertEquals(expectedCrc, success.crc)
        assertEquals(SessionDownloadStatus.Completed, store.load(42L)!!.status)
        // Spot-check without loading the whole file into a single array.
        val partial = files.open(success.partialPath)
        try {
            assertEquals(size, partial.size())
            assertEquals(content.byteAt(0), partial.readAt(0, 1)[0])
            assertEquals(content.byteAt(size - 1), partial.readAt(size - 1, 1)[0])
            assertEquals(content.byteAt(size / 2), partial.readAt(size / 2, 1)[0])
        } finally {
            partial.close()
        }
    }

    @Test
    fun disconnect_atOffsets_resumesWithoutGapsOrDuplicates() = runBlocking {
        val content = SyntheticSessionContent(sizeBytes = 2_500)
        val expectedCrc = streamCrc(content)
        val sessionId = 1_700_000_042L
        val dir = tempDir()
        val store = FileSessionDownloadProgressStore(File(dir, "ckpt"))
        val files = FileSessionPartialFileFactory(File(dir, "partials"))
        val engine = SessionDownloadEngine(store, files)

        val (publicPem, privatePem) = rsaKeyPairToPem()
        val keys = testKeys(publicPem, privatePem)
        val cipher = createSessionCipher()
        val peripheral = FakePlaudPeripheral(
            cipher = cipher,
            clientKeys = keys,
            sessionContent = content,
            sessionId = sessionId,
            requirePublicKey = false,
            maxChunk = 100,
        )
        val ble = FakeBleTransport(peripheral)
        ble.connect("fake-note-pro")
        val client = PlaudDeviceClient(ble, cipher, keys, responseTimeout = 2.seconds)
        client.unlock()
        client.handshake()

        val dropOffsets = listOf(400L, 900L, 1_700L)
        for (dropAt in dropOffsets) {
            peripheral.fault = DownloadFault.DropAtOffset(dropAt)
            assertFailsWith<TimeoutCancellationException> {
                engine.download(sessionId, content.size()) { sink ->
                    client.downloadInto(sink)
                }
            }
            val ckpt = assertNotNull(store.load(sessionId))
            assertEquals(SessionDownloadStatus.InProgress, ckpt.status)
            assertTrue(ckpt.verifiedOffset > 0L)
            assertTrue(ckpt.verifiedOffset <= dropAt + 100)
            // Simulate process death: new engine + client on same durable store.
            peripheral.fault = DownloadFault.None
            peripheral.resetUnlock()
            ble.connect("fake-note-pro")
            client.unlock()
            client.handshake()
        }

        peripheral.fault = DownloadFault.None
        val success = engine.download(sessionId, content.size()) { sink ->
            client.downloadInto(sink)
        }
        assertEquals(content.size(), success.sizeBytes)
        assertEquals(expectedCrc, success.crc)
        assertEquals(SessionDownloadStatus.Completed, store.load(sessionId)!!.status)
        assertPartialMatches(content, files, success.partialPath)
        client.close()
    }

    @Test
    fun processDeath_persistedCheckpoint_resumesFromVerifiedOffset() = runBlocking {
        val content = SyntheticSessionContent(sizeBytes = 1_200)
        val expectedCrc = streamCrc(content)
        val sessionId = 99L
        val dir = tempDir()
        val ckptDir = File(dir, "ckpt")
        val partialDir = File(dir, "partials")

        val (publicPem, privatePem) = rsaKeyPairToPem()
        val keys = testKeys(publicPem, privatePem)
        val cipher = createSessionCipher()
        val peripheral = FakePlaudPeripheral(
            cipher, keys, content, sessionId = sessionId, requirePublicKey = false,
        )
        val ble = FakeBleTransport(peripheral)
        ble.connect("fake-note-pro")
        var client = PlaudDeviceClient(ble, cipher, keys, responseTimeout = 2.seconds)
        client.unlock()
        client.handshake()

        val store1 = FileSessionDownloadProgressStore(ckptDir)
        val engine1 = SessionDownloadEngine(store1, FileSessionPartialFileFactory(partialDir))
        peripheral.fault = DownloadFault.DropAtOffset(500)
        assertFailsWith<TimeoutCancellationException> {
            engine1.download(sessionId, content.size()) { client.downloadInto(it) }
        }
        val mid = assertNotNull(store1.load(sessionId))
        assertTrue(mid.verifiedOffset in 1 until content.size())
        client.close()

        // New process: reload store from disk, new client.
        peripheral.fault = DownloadFault.None
        peripheral.resetUnlock()
        ble.connect("fake-note-pro")
        client = PlaudDeviceClient(ble, cipher, keys, responseTimeout = 2.seconds)
        client.unlock()
        client.handshake()
        val store2 = FileSessionDownloadProgressStore(ckptDir)
        val engine2 = SessionDownloadEngine(store2, FileSessionPartialFileFactory(partialDir))
        val reloaded = assertNotNull(store2.load(sessionId))
        assertEquals(mid.verifiedOffset, reloaded.verifiedOffset)
        assertEquals(mid.runningCrc, reloaded.runningCrc)

        val success = engine2.download(sessionId, content.size()) { client.downloadInto(it) }
        assertEquals(expectedCrc, success.crc)
        assertPartialMatches(content, FileSessionPartialFileFactory(partialDir), success.partialPath)
        client.close()
    }

    @Test
    fun corruptBytes_cannotReportCrcSuccess_andRetryRecovers() = runBlocking {
        val content = SyntheticSessionContent(sizeBytes = 800)
        val expectedCrc = streamCrc(content)
        val sessionId = 7L
        val store = InMemorySessionDownloadProgressStore()
        val files = FileSessionPartialFileFactory(tempDir())
        val engine = SessionDownloadEngine(store, files)

        val (publicPem, privatePem) = rsaKeyPairToPem()
        val keys = testKeys(publicPem, privatePem)
        val cipher = createSessionCipher()
        val peripheral = FakePlaudPeripheral(
            cipher, keys, content, sessionId = sessionId, requirePublicKey = false,
        )
        val ble = FakeBleTransport(peripheral)
        ble.connect("fake-note-pro")
        val client = PlaudDeviceClient(ble, cipher, keys)
        client.unlock()
        client.handshake()

        peripheral.fault = DownloadFault.CorruptAtOffset(250)
        val mismatch = assertFailsWith<SessionCrcMismatchException> {
            engine.download(sessionId, content.size()) { client.downloadInto(it) }
        }
        assertEquals(SessionDownloadStatus.CrcFailed, store.load(sessionId)!!.status)
        assertNotNull(mismatch.partialPath)
        assertTrue(File(mismatch.partialPath!!).exists(), "partial must remain recoverable")

        peripheral.fault = DownloadFault.None
        peripheral.resetUnlock()
        ble.connect("fake-note-pro")
        client.unlock()
        client.handshake()
        val success = engine.download(sessionId, content.size()) { client.downloadInto(it) }
        assertEquals(expectedCrc, success.crc)
        assertEquals(SessionDownloadStatus.Completed, store.load(sessionId)!!.status)
        assertPartialMatches(content, files, success.partialPath)
        client.close()
    }

    @Test
    fun truncatedFinalChunk_failsClearly() = runBlocking {
        val content = SyntheticSessionContent(sizeBytes = 500)
        val sessionId = 8L
        val engine = SessionDownloadEngine(
            InMemorySessionDownloadProgressStore(),
            FileSessionPartialFileFactory(tempDir()),
        )
        val (publicPem, privatePem) = rsaKeyPairToPem()
        val keys = testKeys(publicPem, privatePem)
        val cipher = createSessionCipher()
        val peripheral = FakePlaudPeripheral(
            cipher, keys, content, sessionId = sessionId, requirePublicKey = false,
        )
        peripheral.fault = DownloadFault.TruncateFinalChunk(200)
        val ble = FakeBleTransport(peripheral)
        ble.connect("fake-note-pro")
        val client = PlaudDeviceClient(ble, cipher, keys)
        client.unlock()
        client.handshake()
        assertFailsWith<SessionDownloadTruncatedException> {
            engine.download(sessionId, content.size()) { client.downloadInto(it) }
        }
        client.close()
    }

    @Test
    fun duplicateChunk_ignoredWithoutDuplicatingBytes() = runBlocking {
        val content = SyntheticSessionContent(sizeBytes = 450)
        val expectedCrc = streamCrc(content)
        val sessionId = 9L
        val files = FileSessionPartialFileFactory(tempDir())
        val engine = SessionDownloadEngine(InMemorySessionDownloadProgressStore(), files)
        val (publicPem, privatePem) = rsaKeyPairToPem()
        val keys = testKeys(publicPem, privatePem)
        val cipher = createSessionCipher()
        val peripheral = FakePlaudPeripheral(
            cipher, keys, content, sessionId = sessionId, requirePublicKey = false, maxChunk = 100,
        )
        peripheral.fault = DownloadFault.DuplicateChunkAt(100)
        val ble = FakeBleTransport(peripheral)
        ble.connect("fake-note-pro")
        val client = PlaudDeviceClient(ble, cipher, keys)
        client.unlock()
        client.handshake()
        val success = engine.download(sessionId, content.size()) { sink ->
            // overlapReader omitted: duplicates are skipped; final size/CRC must still match.
            client.downloadInto(sink)
        }
        assertEquals(content.size(), success.sizeBytes)
        assertEquals(expectedCrc, success.crc)
        assertPartialMatches(content, files, success.partialPath)
        client.close()
    }

    @Test
    fun wifiToBleFallback_verifiesCrc() = runBlocking {
        val content = SyntheticSessionContent(sizeBytes = 300)
        val expectedCrc = streamCrc(content)
        val sessionId = 11L
        val (publicPem, privatePem) = rsaKeyPairToPem()
        val keys = testKeys(publicPem, privatePem)
        val cipher = createSessionCipher()
        val peripheral = FakePlaudPeripheral(
            cipher, keys, content, sessionId = sessionId, requirePublicKey = false,
        )
        val ble = FakeBleTransport(peripheral)
        ble.connect("fake-note-pro")
        val client = PlaudDeviceClient(ble, cipher, keys)
        client.unlock()
        client.handshake()

        val coordinator = WifiTransferCoordinator(
            sessionFactory = {
                WifiFastTransferSession(
                    softAp = UnsupportedWifiSoftApJoiner,
                    server = JavaWebsocketTransferServer(),
                    frameCipher = createWifiFrameCipher(
                        cipher,
                        client.sessionSecret!!,
                        WifiPayloadCipher.ChaCha20Poly1305,
                    ),
                )
            },
            bleDownload = {
                client.downloadSession(sessionId, content.size())
            },
        )
        val outcome = coordinator.transferOrFallback(
            ssid = "x",
            password = "y",
            assemble = { error("wifi should fail") },
        )
        assertIs<WifiTransferCoordinator.Outcome.BleFallback>(outcome)
        assertEquals(content.size().toInt(), outcome.bytes.size)
        assertEquals(expectedCrc, Crc16Ccitt.compute(outcome.bytes))
        client.close()
    }

    @Test
    fun wifiToBleFallback_crcMismatch_notSuccessful() = runBlocking {
        val content = SyntheticSessionContent(sizeBytes = 200)
        val sessionId = 12L
        val (publicPem, privatePem) = rsaKeyPairToPem()
        val keys = testKeys(publicPem, privatePem)
        val cipher = createSessionCipher()
        val peripheral = FakePlaudPeripheral(
            cipher, keys, content, sessionId = sessionId, requirePublicKey = false,
        )
        peripheral.forceWrongSyncStopCrc = true
        val ble = FakeBleTransport(peripheral)
        ble.connect("fake-note-pro")
        val client = PlaudDeviceClient(ble, cipher, keys)
        client.unlock()
        client.handshake()

        val coordinator = WifiTransferCoordinator(
            sessionFactory = {
                WifiFastTransferSession(
                    softAp = UnsupportedWifiSoftApJoiner,
                    server = JavaWebsocketTransferServer(),
                    frameCipher = createWifiFrameCipher(
                        cipher,
                        client.sessionSecret!!,
                        WifiPayloadCipher.ChaCha20Poly1305,
                    ),
                )
            },
            bleDownload = { client.downloadSession(sessionId, content.size()) },
        )
        // bleDownload throws — coordinator treats it as another failure path via throwable catch
        // after wifi fails. Ensure CRC mismatch surfaces and is not a silent success.
        assertFailsWith<SessionCrcMismatchException> {
            withTimeout(15_000) {
                coordinator.transferOrFallback(
                    ssid = "x",
                    password = "y",
                    assemble = { error("wifi should fail") },
                )
            }
        }
        client.close()
    }

    @Test
    fun repeatedDrops_thenComplete() = runBlocking {
        val content = SyntheticSessionContent(sizeBytes = 1_000)
        val expectedCrc = streamCrc(content)
        val sessionId = 13L
        val store = InMemorySessionDownloadProgressStore()
        val files = FileSessionPartialFileFactory(tempDir())
        val engine = SessionDownloadEngine(store, files)
        val (publicPem, privatePem) = rsaKeyPairToPem()
        val keys = testKeys(publicPem, privatePem)
        val cipher = createSessionCipher()
        val peripheral = FakePlaudPeripheral(
            cipher, keys, content, sessionId = sessionId, requirePublicKey = false,
        )
        val ble = FakeBleTransport(peripheral)
        ble.connect("fake-note-pro")
        val client = PlaudDeviceClient(ble, cipher, keys, responseTimeout = 2.seconds)
        client.unlock()
        client.handshake()

        repeat(3) { i ->
            peripheral.fault = DownloadFault.DropAtOffset(200L * (i + 1))
            runCatching {
                engine.download(sessionId, content.size()) { client.downloadInto(it) }
            }
            peripheral.fault = DownloadFault.None
            peripheral.resetUnlock()
            ble.connect("fake-note-pro")
            client.unlock()
            client.handshake()
        }
        val success = engine.download(sessionId, content.size()) { client.downloadInto(it) }
        assertEquals(expectedCrc, success.crc)
        assertEquals(SessionDownloadStatus.Completed, store.load(sessionId)!!.status)
        assertPartialMatches(content, files, success.partialPath)
        client.close()
    }

    private fun assertPartialMatches(
        content: SyntheticSessionContent,
        files: FileSessionPartialFileFactory,
        path: String,
    ) {
        val partial = files.open(path)
        try {
            assertEquals(content.size(), partial.size())
            var offset = 0L
            val bufSize = 4096
            while (offset < content.size()) {
                val n = minOf(bufSize.toLong(), content.size() - offset).toInt()
                val expected = content.slice(offset, n)
                val actual = partial.readAt(offset, n)
                assertTrue(expected.contentEquals(actual), "mismatch at offset=$offset")
                offset += n
            }
        } finally {
            partial.close()
        }
    }

    private fun streamCrc(content: SyntheticSessionContent): Int {
        var crc = Crc16Ccitt.INITIAL
        var offset = 0L
        val buf = ByteArray(8192)
        while (offset < content.size()) {
            val n = minOf(buf.size.toLong(), content.size() - offset).toInt()
            content.copyInto(offset, n, buf, 0)
            crc = Crc16Ccitt.update(crc, if (n == buf.size) buf else buf.copyOf(n))
            offset += n
        }
        return crc
    }

    private fun tempDir(): File =
        Files.createTempDirectory("velum-resume-crc-").toFile().also { it.deleteOnExit() }

    private fun testKeys(publicPem: String, privatePem: String) = DeviceIdentityKeys(
        serialNumber = "881FAKE1",
        serialSignature = ByteArray(256) { (it % 251).toByte() },
        publicKeyPem = publicPem,
        privateKeyPem = privatePem,
        bindToken = "tok",
    )
}
