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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class DesktopMicCaptureTest {

    private class FakeAudioLine(
        override val sampleRateHz: Int = 16_000,
        override val channels: Int = 1,
        override val bufferSizeBytes: Int = 4096,
        private val chunkProvider: (() -> ByteArray?)? = null,
        private val onOpenAction: (() -> Unit)? = null,
        private val blockOnReadUntilStopped: Boolean = false,
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

        override fun open() {
            onOpenAction?.invoke()
            openCount.incrementAndGet()
        }

        override fun start() {
            isLineRunning = true
            startCount.incrementAndGet()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (!isLineRunning || isLineClosed) return -1
            if (blockOnReadUntilStopped) {
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
    fun session_token_is_strictly_positive_and_matches_state_immediately() = runBlocking {
        val tempDir = Files.createTempDirectory("velum-mic-token-test").toFile()
        try {
            val audioFile = File(tempDir, "recordings/test.wav")
            val fakeLine = FakeAudioLine(sampleRateHz = 16_000, channels = 1)
            val capture = DesktopMicCapture(
                lineFactory = { fakeLine },
                timeProvider = { 10_000L },
            )

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
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun stale_session_commands_are_rejected() = runBlocking {
        val tempDir = Files.createTempDirectory("velum-mic-stale-test").toFile()
        try {
            val audioFile = File(tempDir, "session_a.wav")
            val fakeLine = FakeAudioLine(sampleRateHz = 16_000, channels = 1)
            val capture = DesktopMicCapture(
                lineFactory = { fakeLine },
                timeProvider = { 1_000L },
            )

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

            val stopResult = assertIs<MicStopResult.Stopped>(capture.stop(activeSession))
            assertEquals(audioFile.absolutePath, stopResult.absolutePath)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun pause_resume_and_marker_math_excludes_paused_time() = runBlocking {
        val tempDir = Files.createTempDirectory("velum-mic-markers-test").toFile()
        try {
            val audioFile = File(tempDir, "markers.wav")
            var currentTime = 10_000L
            val fakeLine = FakeAudioLine(sampleRateHz = 16_000, channels = 1)
            val capture = DesktopMicCapture(
                lineFactory = { fakeLine },
                timeProvider = { currentTime },
            )

            val startResult = assertIs<MicStartResult.Started>(capture.start(audioFile.absolutePath))
            val session = startResult.session

            // At t = 12,000 (+2,000ms), drop m1
            currentTime = 12_000L
            val m1 = assertIs<MicMarkerResult.Ok>(capture.addMarker(session, "m1"))
            assertEquals(2_000L, m1.marker.offsetMs)

            // At t = 14,000, pause
            currentTime = 14_000L
            assertEquals(MicPauseResult.Ok, capture.pause(session))
            assertTrue(capture.isPaused)

            // At t = 19,000 (paused for 5,000ms), resume
            currentTime = 19_000L
            assertEquals(MicPauseResult.Ok, capture.resume(session))
            assertFalse(capture.isPaused)

            // At t = 22,000 (+3,000ms recorded, total elapsed 12,000ms - 5,000ms paused = 7,000ms), drop m2
            currentTime = 22_000L
            val m2 = assertIs<MicMarkerResult.Ok>(capture.addMarker(session, "m2"))
            assertEquals(7_000L, m2.marker.offsetMs)

            // At t = 25,000, pause again
            currentTime = 25_000L
            assertEquals(MicPauseResult.Ok, capture.pause(session))

            // At t = 27,000 (while paused), drop m3 (total active recorded time = 10,000ms)
            currentTime = 27_000L
            val m3 = assertIs<MicMarkerResult.Ok>(capture.addMarker(session, "m3"))
            assertEquals(10_000L, m3.marker.offsetMs)

            val markers = capture.markers
            assertEquals(3, markers.size)
            assertEquals(2_000L, markers[0].offsetMs)
            assertEquals(7_000L, markers[1].offsetMs)
            assertEquals(10_000L, markers[2].offsetMs)

            capture.stop(session)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun streaming_downmix_and_resampling_from_48k_stereo_to_16k_mono() = runBlocking {
        val tempDir = Files.createTempDirectory("velum-mic-resample-test").toFile()
        try {
            val audioFile = File(tempDir, "resampled_48k_stereo.wav")

            // Emit 48 kHz stereo (4 bytes per frame)
            // 480 frames = 10ms of 48k audio = 1,920 bytes.
            // When resampled to 16 kHz mono (2 bytes per frame), 480 frames becomes 160 frames = 320 bytes.
            val emittedChunks = AtomicInteger(0)
            val chunk = ByteArray(1920) { idx -> ((idx * 7) % 256).toByte() }

            val fakeLine = FakeAudioLine(
                sampleRateHz = 48_000,
                channels = 2,
                bufferSizeBytes = 1920,
                chunkProvider = {
                    if (emittedChunks.getAndIncrement() < 20) chunk else null
                },
            )

            val capture = DesktopMicCapture(lineFactory = { fakeLine })
            val startResult = assertIs<MicStartResult.Started>(capture.start(audioFile.absolutePath))
            val session = startResult.session

            val livePcmChunks = CopyOnWriteArrayList<ByteArray>()
            assertTrue(capture.attachPcm(session) { pcm ->
                livePcmChunks.add(pcm)
            })

            // Wait until chunks are processed
            var attempts = 0
            while (emittedChunks.get() < 20 && attempts++ < 40) {
                kotlinx.coroutines.delay(25)
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
                assertEquals(16, bb.getInt(16)) // PCM format size
                assertEquals(1.toShort(), bb.getShort(20)) // PCM format code 1
                assertEquals(1.toShort(), bb.getShort(22)) // 1 channel (mono)
                assertEquals(16_000, bb.getInt(24)) // 16,000 Hz target sample rate
                assertEquals(32_000, bb.getInt(28)) // byte rate (16000 * 2)
                assertEquals(2.toShort(), bb.getShort(32)) // block align
                assertEquals(16.toShort(), bb.getShort(34)) // 16-bit
                assertEquals("data", String(header, 36, 4))
                val dataSize = bb.getInt(40).toLong()
                assertEquals(raf.length() - 44L, dataSize)
            }

            assertTrue(livePcmChunks.isNotEmpty(), "Live PCM chunks must have been delivered to attachPcm listener")
            livePcmChunks.forEach { pcm ->
                assertEquals(0, pcm.size % 2, "PCM chunk size must be multiple of 2 (16-bit samples)")
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
            )

            val capture = DesktopMicCapture(lineFactory = { fakeLine })
            val startResult = assertIs<MicStartResult.Started>(capture.start(audioFile.absolutePath))
            val session = startResult.session

            // Line should be open and started
            assertEquals(1, fakeLine.openCount.get())
            assertEquals(1, fakeLine.startCount.get())

            // stop must unblock the read loop and settle without timing out
            val stopResult = assertIs<MicStopResult.Stopped>(capture.stop(session))
            assertEquals(1, fakeLine.stopCount.get())
            assertEquals(1, fakeLine.closeCount.get())
            assertEquals(audioFile.absolutePath, stopResult.absolutePath)
            assertEquals(MicSessionState.Stopped(audioFile.absolutePath, 0L, 44L, session.token), capture.sessions.value)
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

            // Must NOT be PermissionRequired
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
    fun desktop_live_pcm_capture_streams_and_stops_cleanly() = runBlocking {
        val sampleChunk = ByteArray(882) { (it * 3).toByte() } // 44.1 kHz mono
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
            kotlinx.coroutines.delay(25)
        }

        liveCapture.stop()
        assertTrue(fakeLine.isLineClosed)

        assertTrue(receivedChunks.isNotEmpty())
        receivedChunks.forEach { pcm ->
            assertEquals(0, pcm.size % 2)
        }
    }
}
