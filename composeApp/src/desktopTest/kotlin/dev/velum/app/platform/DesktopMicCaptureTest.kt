package dev.velum.app.platform

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.sound.sampled.LineUnavailableException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

class DesktopMicCaptureTest {

    private class FakeAudioLine(
        override val sampleRateHz: Int = 16_000,
        override val channels: Int = 1,
        override val bufferSizeBytes: Int = 4096,
        private val chunkProvider: (() -> ByteArray?)? = null,
        private val onOpenAction: (() -> Unit)? = null,
        private val onStartAction: (() -> Unit)? = null,
        private val blockOnReadUntilStopped: Boolean = false,
        private val hangForeverOnRead: Boolean = false,
    ) : DesktopAudioLine {
        val openCount = AtomicInteger(0)
        val startCount = AtomicInteger(0)
        val stopCount = AtomicInteger(0)
        val closeCount = AtomicInteger(0)

        @Volatile
        var isLineRunning = false

        @Volatile
        var isLineClosed = false

        private val stopLatch = CountDownLatch(1)
        private val initialChunkRead = AtomicBoolean(false)
        val hangLatch = CountDownLatch(1)
        val readEntered = CountDownLatch(1)

        override fun open() {
            onOpenAction?.invoke()
            openCount.incrementAndGet()
        }

        override fun start() {
            onStartAction?.invoke()
            isLineRunning = true
            startCount.incrementAndGet()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (!isLineRunning || isLineClosed) return -1
            if (hangForeverOnRead) {
                readEntered.countDown()
                try {
                    hangLatch.await(10, TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                    return -1
                }
                return -1
            }
            if (blockOnReadUntilStopped) {
                if (chunkProvider != null && initialChunkRead.compareAndSet(false, true)) {
                    val chunk = chunkProvider.invoke() ?: return -1
                    val size = minOf(chunk.size, length)
                    System.arraycopy(chunk, 0, buffer, offset, size)
                    return size
                }
                try {
                    stopLatch.await(500, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    return -1
                }
                return -1
            }
            val chunk = chunkProvider?.invoke() ?: return -1
            val toCopy = minOf(chunk.size, length)
            System.arraycopy(chunk, 0, buffer, offset, toCopy)
            return toCopy
        }

        override fun stop() {
            isLineRunning = false
            stopCount.incrementAndGet()
            stopLatch.countDown()
        }

        override fun close() {
            isLineRunning = false
            isLineClosed = true
            closeCount.incrementAndGet()
            stopLatch.countDown()
        }
    }

    @Test
    fun worker_start_failure_publishes_failed() = runBlocking {
        val dir = Files.createTempDirectory("mic-start-failure").toFile()
        try {
            val line = FakeAudioLine(onStartAction = { error("start failed") })
            val capture = DesktopMicCapture(lineFactory = { line })
            capture.start(File(dir, "test.wav").absolutePath)
            repeat(100) { if (capture.sessions.value !is MicSessionState.Failed) delay(10) }
            assertTrue(assertIs<MicSessionState.Failed>(capture.sessions.value).message.contains("start failed"))
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun checkpoint_failure_publishes_failed_and_finalizes_wav() = runBlocking {
        val dir = Files.createTempDirectory("mic-checkpoint-failure").toFile()
        try {
            val file = File(dir, "test.wav")
            val line = FakeAudioLine(bufferSizeBytes = 32_000, chunkProvider = { ByteArray(32_000) })
            val capture = DesktopMicCapture(lineFactory = { line }, checkpoint = { error("checkpoint failed") })
            capture.start(file.absolutePath)
            repeat(100) { if (line.closeCount.get() < 2) delay(10) }
            assertTrue(assertIs<MicSessionState.Failed>(capture.sessions.value).message.contains("checkpoint failed"))
            RandomAccessFile(file, "r").use { raf ->
                val header = ByteArray(44)
                raf.readFully(header)
                assertTrue(raf.length() > 44)
                assertEquals(raf.length() - 44, ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getInt(40).toLong())
            }
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun failure_finalizes_written_wav() = runBlocking {
        val dir = Files.createTempDirectory("mic-failure-header").toFile()
        try {
            val file = File(dir, "test.wav")
            val count = AtomicInteger()
            val line = FakeAudioLine(chunkProvider = { if (count.getAndIncrement() == 0) ByteArray(4096) else null })
            val capture = DesktopMicCapture(lineFactory = { line })
            capture.start(file.absolutePath)
            repeat(100) { if (line.closeCount.get() < 2) delay(10) }
            RandomAccessFile(file, "r").use { raf ->
                val header = ByteArray(44)
                raf.readFully(header)
                assertTrue(raf.length() > 44)
                assertEquals(raf.length() - 44, ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getInt(40).toLong())
            }
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun pre_existing_file_preservation_atomic_create_new() = runBlocking {
        val tempDir = Files.createTempDirectory("velum-mic-preexist-test").toFile()
        try {
            val audioFile = File(tempDir, "existing.wav")
            val originalBytes = "PRE_EXISTING_DO_NOT_OVERWRITE".toByteArray()
            audioFile.writeBytes(originalBytes)

            val fakeLine = FakeAudioLine()
            val capture = DesktopMicCapture(lineFactory = { fakeLine })

            val result = capture.start(audioFile.absolutePath)
            val failed = assertIs<MicStartResult.Failed>(result)
            assertTrue(failed.message.contains("already exists"))

            // Pre-existing file content must be completely untouched
            assertContentEquals(originalBytes, audioFile.readBytes())
            assertEquals(0, fakeLine.openCount.get(), "Line must not have been opened")
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun session_token_is_strictly_positive_and_matches_state_immediately() = runBlocking {
        val tempDir = Files.createTempDirectory("velum-mic-token-test").toFile()
        try {
            val audioFile = File(tempDir, "recordings/test.wav")
            val fakeLine = FakeAudioLine(sampleRateHz = 16_000, channels = 1, blockOnReadUntilStopped = true)
            val capture = DesktopMicCapture(lineFactory = { fakeLine })

            val controller = MicRecordingController(capture)
            val startResult = controller.startOrAttach("rec-1", audioFile.absolutePath)
            val started = assertIs<MicStartResult.Started>(startResult)

            assertTrue(started.session.token > 0L, "Session token must be strictly positive")
            assertEquals(audioFile.absolutePath, started.session.absolutePath)

            val currentState = capture.sessions.value
            val recordingState = assertIs<MicSessionState.Recording>(currentState)
            assertEquals(started.session, recordingState.session)
            assertTrue(capture.isCapturing)
            assertFalse(capture.isPaused)

            // Duplicate start while capturing fails
            val secondStart = capture.start(File(tempDir, "other.wav").absolutePath)
            assertIs<MicStartResult.Failed>(secondStart)

            capture.stop(started.session)
            Unit
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun stale_session_commands_are_rejected() = runBlocking {
        val tempDir = Files.createTempDirectory("velum-mic-stale-test").toFile()
        try {
            val audioFile = File(tempDir, "session_a.wav")
            val fakeLine = FakeAudioLine(sampleRateHz = 16_000, channels = 1, blockOnReadUntilStopped = true, chunkProvider = { ByteArray(4096) })
            val capture = DesktopMicCapture(lineFactory = { fakeLine })

            val startResult = assertIs<MicStartResult.Started>(capture.start(audioFile.absolutePath))
            val activeSession = startResult.session

            val staleSession = MicSessionIdentity(activeSession.token + 999L, "/non/existent/stale.wav")

            assertEquals(MicPauseResult.NotCapturing, capture.pause(staleSession))
            assertEquals(MicPauseResult.NotCapturing, capture.resume(staleSession))
            assertEquals(MicMarkerResult.NotCapturing, capture.addMarker(staleSession, "ignored"))
            assertEquals(MicStopResult.NotCapturing, capture.stop(staleSession))
            assertFalse(capture.attachPcm(staleSession) {})

            // Real session still functions
            assertEquals(MicPauseResult.Ok, capture.pause(activeSession))
            assertEquals(MicPauseResult.Ok, capture.resume(activeSession))
            val marker = assertIs<MicMarkerResult.Ok>(capture.addMarker(activeSession, "valid"))
            assertEquals("valid", marker.marker.label)

            repeat(100) { if (audioFile.length() <= 44) delay(10) }
            assertTrue(audioFile.length() > 44)
            val stopResult = assertIs<MicStopResult.Stopped>(capture.stop(activeSession))
            assertEquals(audioFile.absolutePath, stopResult.absolutePath)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun markers_follow_output_pcm_frames_not_wall_time() = runBlocking {
        val tempDir = Files.createTempDirectory("velum-mic-pcm-markers-test").toFile()
        try {
            val audioFile = File(tempDir, "pcm_markers.wav")

            // 16,000 Hz mono 16-bit PCM = 32,000 bytes per second = 32 bytes per millisecond
            // Emit exactly 32,000 bytes (1,000 ms of audio)
            val chunk1 = ByteArray(32_000) { 1 }
            val chunk2 = ByteArray(16_000) { 2 }
            val allowSecond = AtomicBoolean(false)
            val emitted = AtomicInteger()

            val fakeLine = FakeAudioLine(
                sampleRateHz = 16_000,
                channels = 1,
                bufferSizeBytes = 32_000,
                chunkProvider = {
                    if (emitted.compareAndSet(0, 1)) chunk1
                    else if (allowSecond.get() && emitted.compareAndSet(1, 2)) chunk2
                    else ByteArray(0)
                },
            )

            val capture = DesktopMicCapture(lineFactory = { fakeLine })
            val startResult = assertIs<MicStartResult.Started>(capture.start(audioFile.absolutePath))
            val session = startResult.session

            // Wait until first 32,000 bytes are read and written (1,000 ms audio)
            repeat(30) {
                if (capture.sessions.value.let { it is MicSessionState.Recording && it.bytesWritten >= 32_000L }) return@repeat
                delay(20)
            }

            // Drop m1: must be exactly 1,000ms based on audio frames
            val m1 = assertIs<MicMarkerResult.Ok>(capture.addMarker(session, "m1"))
            assertEquals(1_000L, m1.marker.offsetMs)

            // Pause capture
            assertEquals(MicPauseResult.Ok, capture.pause(session))

            // Drop m2 while paused: offset must stay exactly 1,000ms (frames did not advance)
            val m2 = assertIs<MicMarkerResult.Ok>(capture.addMarker(session, "m2"))
            assertEquals(1_000L, m2.marker.offsetMs)

            allowSecond.set(true)
            // Resume capture: second chunk (16,000 bytes = 500 ms) is processed
            assertEquals(MicPauseResult.Ok, capture.resume(session))

            repeat(30) {
                if (capture.sessions.value.let { it is MicSessionState.Recording && it.bytesWritten >= 48_000L }) return@repeat
                delay(20)
            }

            // Drop m3: must be 1,000ms + 500ms = 1,500ms
            val m3 = assertIs<MicMarkerResult.Ok>(capture.addMarker(session, "m3"))
            assertEquals(1_500L, m3.marker.offsetMs)

            capture.stop(session)
            Unit
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun pause_does_not_publish_recording_state_from_in_flight_read() = runBlocking {
        val tempDir = Files.createTempDirectory("velum-mic-pause-race-test").toFile()
        try {
            val audioFile = File(tempDir, "pause_race.wav")

            val chunk = ByteArray(32_000) { 1 }
            val fakeLine = FakeAudioLine(
                sampleRateHz = 16_000,
                channels = 1,
                bufferSizeBytes = 4096,
                chunkProvider = { chunk },
            )

            val capture = DesktopMicCapture(lineFactory = { fakeLine })
            val startResult = assertIs<MicStartResult.Started>(capture.start(audioFile.absolutePath))
            val session = startResult.session

            // Pause immediately
            capture.pause(session)
            assertTrue(capture.isPaused)
            val stateBefore = capture.sessions.value
            assertIs<MicSessionState.Paused>(stateBefore)

            // Allow the worker thread to loop several times while paused
            delay(100)

            // Verify that in-flight reads while paused did NOT clobber the Paused state with Recording
            val stateAfter = capture.sessions.value
            assertIs<MicSessionState.Paused>(stateAfter, "In-flight reads while paused must not publish Recording state")

            capture.stop(session)
            Unit
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun eof_read_transitions_to_failed_and_releases_resources() = runBlocking {
        val tempDir = Files.createTempDirectory("velum-mic-eof-test").toFile()
        try {
            val audioFile = File(tempDir, "eof.wav")

            val fakeLine = FakeAudioLine(
                sampleRateHz = 16_000,
                channels = 1,
                chunkProvider = { null }, // null causes read to return -1 (EOF)
            )

            val capture = DesktopMicCapture(lineFactory = { fakeLine })
            val startResult = assertIs<MicStartResult.Started>(capture.start(audioFile.absolutePath))
            val session = startResult.session

            // Wait for EOF detection
            repeat(40) {
                if (capture.sessions.value is MicSessionState.Failed) return@repeat
                delay(20)
            }

            val state = assertIs<MicSessionState.Failed>(capture.sessions.value)
            assertTrue(state.message.contains("EOF") || state.message.contains("terminated"))
            assertFalse(state.permissionRequired)
            assertFalse(capture.isCapturing, "EOF must end capturing state")
            assertTrue(fakeLine.isLineClosed, "EOF must close line resources")
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun throwing_pcm_callback_does_not_crash_loop_and_releases_resources() = runBlocking {
        val tempDir = Files.createTempDirectory("velum-mic-callback-crash-test").toFile()
        try {
            val audioFile = File(tempDir, "crash.wav")
            val chunk = ByteArray(1024) { 1 }

            val fakeLine = FakeAudioLine(
                sampleRateHz = 16_000,
                channels = 1,
                chunkProvider = { chunk },
            )

            val capture = DesktopMicCapture(lineFactory = { fakeLine })
            val startResult = assertIs<MicStartResult.Started>(capture.start(audioFile.absolutePath))
            val session = startResult.session

            // Attach listener that throws
            capture.attachPcm(session) {
                throw RuntimeException("Boom in listener")
            }

            // Wait for callback failure handling
            repeat(40) {
                if (capture.sessions.value is MicSessionState.Failed) return@repeat
                delay(20)
            }

            val state = assertIs<MicSessionState.Failed>(capture.sessions.value)
            assertTrue(state.message.contains("callback threw") || state.message.contains("Boom in listener"))
            assertFalse(capture.isCapturing)
            assertTrue(fakeLine.isLineClosed)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun streaming_downmix_and_resampling_from_48k_stereo_to_16k_mono() = runBlocking {
        val tempDir = Files.createTempDirectory("velum-mic-resample-test").toFile()
        try {
            val audioFile = File(tempDir, "resampled_48k_stereo.wav")

            val emittedChunks = AtomicInteger(0)
            val chunk = ByteArray(1920) { idx -> ((idx * 7) % 256).toByte() }

            val fakeLine = FakeAudioLine(
                sampleRateHz = 48_000,
                channels = 2,
                bufferSizeBytes = 1920,
                chunkProvider = {
                    if (emittedChunks.getAndIncrement() < 20) chunk else ByteArray(0)
                },
            )

            val capture = DesktopMicCapture(lineFactory = { fakeLine })
            val startResult = assertIs<MicStartResult.Started>(capture.start(audioFile.absolutePath))
            val session = startResult.session

            val livePcmChunks = CopyOnWriteArrayList<ByteArray>()
            assertTrue(capture.attachPcm(session) { pcm ->
                livePcmChunks.add(pcm)
            })

            var attempts = 0
            while (emittedChunks.get() < 20 && attempts++ < 40) {
                delay(25)
            }

            val stopResult = assertIs<MicStopResult.Stopped>(capture.stop(session))
            assertTrue(stopResult.sizeBytes >= 44L)
            assertTrue(audioFile.exists())

            // Verify WAV header matches 16 kHz mono
            RandomAccessFile(audioFile, "r").use { raf ->
                val header = ByteArray(44)
                raf.readFully(header)
                val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                assertEquals("RIFF", String(header, 0, 4))
                assertEquals("WAVE", String(header, 8, 4))
                assertEquals("fmt ", String(header, 12, 4))
                assertEquals(16, bb.getInt(16))
                assertEquals(1.toShort(), bb.getShort(20))
                assertEquals(1.toShort(), bb.getShort(22))
                assertEquals(16_000, bb.getInt(24))
                assertEquals(32_000, bb.getInt(28))
                assertEquals(2.toShort(), bb.getShort(32))
                assertEquals(16.toShort(), bb.getShort(34))
                assertEquals("data", String(header, 36, 4))
                val dataSize = bb.getInt(40).toLong()
                assertEquals(raf.length() - 44L, dataSize)
            }

            assertTrue(livePcmChunks.isNotEmpty())
            livePcmChunks.forEach { pcm ->
                assertEquals(0, pcm.size % 2)
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun stop_closes_and_unblocks_line_before_worker_settlement_without_deadlock() = runBlocking {
        val tempDir = Files.createTempDirectory("velum-mic-stop-test").toFile()
        try {
            val audioFile = File(tempDir, "unblock_test.wav")
            val fakeLine = FakeAudioLine(
                sampleRateHz = 16_000,
                channels = 1,
                blockOnReadUntilStopped = true,
                chunkProvider = { ByteArray(4096) },
            )

            val capture = DesktopMicCapture(lineFactory = { fakeLine })
            val startResult = assertIs<MicStartResult.Started>(capture.start(audioFile.absolutePath))
            val session = startResult.session

            assertEquals(1, fakeLine.openCount.get())
            repeat(100) { if (fakeLine.startCount.get() == 0) delay(10) }
            assertEquals(1, fakeLine.startCount.get())

            repeat(100) { if (audioFile.length() <= 44) delay(10) }
            assertTrue(audioFile.length() > 44)
            val stopResult = assertIs<MicStopResult.Stopped>(capture.stop(session))
            assertTrue(fakeLine.stopCount.get() >= 1)
            assertTrue(fakeLine.closeCount.get() >= 1)
            assertEquals(audioFile.absolutePath, stopResult.absolutePath)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun generic_line_unavailable_exception_is_not_treated_as_permission_denial() = runBlocking {
        val tempDir = Files.createTempDirectory("velum-mic-line-busy-test").toFile()
        try {
            val audioFile = File(tempDir, "line_busy.wav")
            val fakeLine = FakeAudioLine(
                onOpenAction = {
                    throw LineUnavailableException("Audio line busy: in exclusive use by another app")
                },
            )

            val capture = DesktopMicCapture(lineFactory = { fakeLine })
            val result = capture.start(audioFile.absolutePath)

            val failed = assertIs<MicStartResult.Failed>(result)
            assertTrue(failed.message.contains("busy") || failed.message.contains("unavailable"))

            val state = assertIs<MicSessionState.Failed>(capture.sessions.value)
            assertFalse(state.permissionRequired, "Generic LineUnavailableException must NOT claim permissionRequired")
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun explicit_security_or_permission_denial_is_mapped_to_permission_required() = runBlocking {
        val tempDir = Files.createTempDirectory("velum-mic-perm-test").toFile()
        try {
            val audioFile = File(tempDir, "perm_denied.wav")
            val fakeLine = FakeAudioLine(
                onOpenAction = {
                    throw SecurityException("Microphone access denied by TCC system policy")
                },
            )

            val capture = DesktopMicCapture(lineFactory = { fakeLine })
            val result = capture.start(audioFile.absolutePath)

            assertIs<MicStartResult.PermissionRequired>(result)
            val state = assertIs<MicSessionState.Failed>(capture.sessions.value)
            assertTrue(state.permissionRequired, "SecurityException must report permissionRequired = true")
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun old_live_worker_cannot_stop_restarted_capture() {
        val oldLine = FakeAudioLine(hangForeverOnRead = true)
        val newLine = FakeAudioLine(blockOnReadUntilStopped = true)
        val creations = AtomicInteger()
        val capture = DesktopLivePcmCapture { if (creations.getAndIncrement() == 0) oldLine else newLine }
        try {
            assertTrue(capture.start { error("old worker published") })
            assertTrue(oldLine.readEntered.await(2, TimeUnit.SECONDS))
            capture.stop()
            assertTrue(capture.start {})
            oldLine.hangLatch.countDown()
            // Wait for the old worker's final cleanup before checking the new generation.
            repeat(100) {
                if (oldLine.closeCount.get() < 2) Thread.sleep(10)
            }
            assertTrue(oldLine.closeCount.get() >= 2)
            assertTrue(capture.start {})
            assertEquals(2, creations.get())
            assertFalse(newLine.isLineClosed)
        } finally {
            oldLine.hangLatch.countDown()
            capture.stop()
        }
    }

    @Test
    fun desktop_live_pcm_capture_closes_line_when_open_fails() {
        val fakeLine = FakeAudioLine(onOpenAction = { throw LineUnavailableException("busy") })
        val capture = DesktopLivePcmCapture(lineFactory = { fakeLine })

        assertFalse(capture.start { error("No PCM expected") })
        assertEquals(1, fakeLine.closeCount.get())
        assertEquals(0, fakeLine.startCount.get())
    }

    @Test
    fun desktop_live_pcm_capture_closes_line_when_start_fails() {
        val fakeLine = FakeAudioLine(onStartAction = { throw IllegalStateException("cannot start") })
        val capture = DesktopLivePcmCapture(lineFactory = { fakeLine })

        assertFalse(capture.start { error("No PCM expected") })
        assertEquals(1, fakeLine.openCount.get())
        assertEquals(1, fakeLine.closeCount.get())
    }

    @Test
    fun desktop_live_pcm_capture_streams_and_stops_cleanly() = runBlocking {
        val sampleChunk = ByteArray(882) { (it * 3).toByte() }
        val chunksEmitted = AtomicInteger(0)
        val fakeLine = FakeAudioLine(
            sampleRateHz = 44_100,
            channels = 1,
            chunkProvider = {
                if (chunksEmitted.getAndIncrement() < 15) sampleChunk else null
            },
        )

        val liveCapture = DesktopLivePcmCapture(lineFactory = { fakeLine })
        assertEquals(16_000, liveCapture.sampleRateHz)

        val receivedChunks = CopyOnWriteArrayList<ByteArray>()
        val started = liveCapture.start { pcm ->
            receivedChunks.add(pcm)
        }
        assertTrue(started)

        var attempts = 0
        while (chunksEmitted.get() < 15 && attempts++ < 30) {
            delay(25)
        }

        liveCapture.stop()
        assertTrue(fakeLine.isLineClosed)

        assertTrue(receivedChunks.isNotEmpty())
        receivedChunks.forEach { pcm ->
            assertEquals(0, pcm.size % 2)
        }
    }
}
