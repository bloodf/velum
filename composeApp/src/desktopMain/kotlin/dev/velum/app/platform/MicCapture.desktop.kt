package dev.velum.app.platform

import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.sound.sampled.LineUnavailableException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

actual fun createMicCapture(): MicCapture = DesktopMicCapture()

internal class DesktopCaptureSession(
    val session: MicSessionIdentity,
    val line: DesktopAudioLine,
    val file: File,
    val raf: RandomAccessFile,
    val resampler: MonoPcmWavWriter,
) {
    val capturing = AtomicBoolean(true)
    val paused = AtomicBoolean(false)
    val settled = AtomicBoolean(false)

    @Volatile
    var workerThread: Thread? = null

    @Volatile
    var pcmListener: ((ByteArray) -> Unit)? = null

    val pcmBytesWritten: Long get() = resampler.pcmBytesWritten

    /**
     * Marker offset in milliseconds based strictly on output PCM frames (16 kHz, 16-bit mono = 32 bytes/ms).
     * Eliminates wall-time drift, thread scheduling latency, and pause accumulation errors.
     */
    val recordedOffsetMs: Long get() = (pcmBytesWritten / 32L).coerceAtLeast(0L)

    fun closeResources() {
        releaseAll(
            { runCatching { line.stop() } },
            { runCatching { line.close() } },
            { runCatching { resampler.close() } },
            { runCatching { raf.close() } },
        )
    }
}

internal class DesktopMicCapture(
    private val lineFactory: DesktopAudioLineFactory = JavaSoundAudioLineFactory,
) : MicCapture {

    private val startMutex = Mutex()
    private val sessionSequence = AtomicLong(0)
    private val sessionLock = Any()
    private val markersLock = Any()

    private val _sessions = MutableStateFlow<MicSessionState>(MicSessionState.Idle)
    override val sessions: StateFlow<MicSessionState> = _sessions.asStateFlow()

    private val _failures = MutableStateFlow<String?>(null)
    override val failures: StateFlow<String?> = _failures.asStateFlow()

    private var activeSession: DesktopCaptureSession? = null
    private val sessionMarkers = ArrayList<MicMarker>()

    override val isSupported: Boolean = true
    override val isCapturing: Boolean
        get() = _sessions.value.let {
            it is MicSessionState.Starting || it is MicSessionState.Recording || it is MicSessionState.Paused
        }
    override val isPaused: Boolean
        get() = _sessions.value is MicSessionState.Paused

    override val markers: List<MicMarker>
        get() = synchronized(markersLock) { sessionMarkers.toList() }

    override var onPcmChunk: ((ByteArray) -> Unit)?
        get() = synchronized(sessionLock) { activeSession?.pcmListener }
        set(value) {
            synchronized(sessionLock) { activeSession?.pcmListener = value }
        }

    override fun attachPcm(session: MicSessionIdentity, listener: (ByteArray) -> Unit): Boolean =
        synchronized(sessionLock) {
            val active = activeSession
            if (active == null || active.session != session || !active.capturing.get()) return false
            active.pcmListener = listener
            true
        }

    override fun detachPcm(session: MicSessionIdentity, listener: (ByteArray) -> Unit) {
        synchronized(sessionLock) {
            val active = activeSession
            if (active != null && active.session == session && active.pcmListener === listener) {
                active.pcmListener = null
            }
        }
    }

    override suspend fun start(
        absolutePath: String,
        inputSource: MicInputSource,
    ): MicStartResult = startMutex.withLock {
        synchronized(sessionLock) {
            val existing = activeSession
            if (existing != null && (!existing.settled.get() || existing.workerThread?.isAlive == true)) {
                return MicStartResult.Failed("Recording already in progress")
            }
        }

        val file = File(absolutePath)
        file.parentFile?.mkdirs()

        // 1. Atomic creation: StandardOpenOption.CREATE_NEW fails if file already exists (no overwrite race)
        try {
            FileChannel.open(
                file.toPath(),
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
            ).use { channel ->
                val header = ByteBuffer.wrap(pcm16MonoWavHeader(0, 16_000))
                while (header.hasRemaining()) {
                    check(channel.write(header) > 0) { "Unable to write WAV header" }
                }
                channel.force(true)
            }
        } catch (_: FileAlreadyExistsException) {
            return MicStartResult.Failed("Recording output already exists: $absolutePath")
        } catch (e: Exception) {
            return MicStartResult.Failed("Failed to initialize recording file: ${e.message}")
        }

        val token = sessionSequence.incrementAndGet()
        val sessionIdentity = MicSessionIdentity(token, absolutePath)

        // 2. Hardware line acquisition with candidate cleanup
        val line = try {
            lineFactory.createLine(inputSource).also { it.open() }
        } catch (e: SecurityException) {
            file.delete()
            val fail = MicSessionState.Failed(
                message = "Microphone permission denied: ${e.message ?: "permission required"}",
                permissionRequired = true,
                session = sessionIdentity,
            )
            _sessions.value = fail
            _failures.value = fail.message
            return MicStartResult.PermissionRequired
        } catch (e: LineUnavailableException) {
            file.delete()
            val fail = MicSessionState.Failed(
                message = "Microphone line unavailable: ${e.message ?: "device busy or unavailable"}",
                permissionRequired = false,
                session = sessionIdentity,
            )
            _sessions.value = fail
            _failures.value = fail.message
            return MicStartResult.Failed(fail.message)
        } catch (e: Exception) {
            file.delete()
            val fail = MicSessionState.Failed(
                message = "Microphone initialization failed: ${e.message ?: "unknown error"}",
                permissionRequired = false,
                session = sessionIdentity,
            )
            _sessions.value = fail
            _failures.value = fail.message
            return MicStartResult.Failed(fail.message)
        }

        val raf = try {
            RandomAccessFile(file, "rw").apply { seek(44) }
        } catch (e: Exception) {
            line.close()
            file.delete()
            val fail = MicSessionState.Failed("Unable to open recording file for writing: ${e.message}", false, sessionIdentity)
            _sessions.value = fail
            _failures.value = fail.message
            return MicStartResult.Failed(fail.message)
        }

        val rafStream = object : OutputStream() {
            override fun write(b: Int) = raf.write(b)
            override fun write(b: ByteArray, off: Int, len: Int) = raf.write(b, off, len)
            override fun flush() {}
            override fun close() {}
        }

        var captureSession: DesktopCaptureSession? = null

        val chunkSink = PcmChunkSink(
            fileOutputStream = rafStream,
            onChunk = { pcmChunk ->
                val current = captureSession
                if (current != null && current.capturing.get() && !current.paused.get()) {
                    try {
                        current.pcmListener?.invoke(pcmChunk)
                    } catch (t: Throwable) {
                        failAndCloseSession(current, "PCM chunk callback threw: ${t.message ?: t::class.simpleName}")
                    }
                }
            },
        )

        val resampler = MonoPcmWavWriter(
            file = null,
            targetRate = 16_000,
            sink = chunkSink,
            writeHeaderPlaceholder = false,
        ).apply {
            setFormat(line.sampleRateHz, line.channels)
        }

        val session = DesktopCaptureSession(
            session = sessionIdentity,
            line = line,
            file = file,
            raf = raf,
            resampler = resampler,
        )
        captureSession = session

        val thread = Thread({
            runCaptureLoop(session)
        }, "velum-desktop-mic").apply {
            isDaemon = true
        }

        // Assign workerThread and register activeSession BEFORE thread.start()
        session.workerThread = thread

        synchronized(sessionLock) {
            activeSession = session
            _failures.value = null
            _sessions.value = MicSessionState.Recording(absolutePath, 0L, token)
        }
        synchronized(markersLock) {
            sessionMarkers.clear()
        }

        thread.start()

        return MicStartResult.Started(sessionIdentity)
    }

    private fun failAndCloseSession(session: DesktopCaptureSession, message: String) {
        if (!session.capturing.getAndSet(false)) return
        session.closeResources()
        val failState = MicSessionState.Failed(message, permissionRequired = false, session = session.session)
        _sessions.value = failState
        _failures.value = message
    }

    private fun runCaptureLoop(session: DesktopCaptureSession) {
        val raf = session.raf
        val line = session.line
        val writer = session.resampler
        val readBuffer = ByteArray(line.bufferSizeBytes.coerceAtLeast(4096))
        var lastSyncedBytes = 0L

        try {
            line.start()

            while (session.capturing.get()) {
                val readBytes = try {
                    line.read(readBuffer, 0, readBuffer.size)
                } catch (e: Exception) {
                    failAndCloseSession(session, "Audio line read failed: ${e.message}")
                    break
                }

                if (!session.capturing.get()) break

                // EOF / audio line closed
                if (readBytes < 0) {
                    failAndCloseSession(session, "Microphone audio input stream terminated unexpectedly (EOF)")
                    break
                }

                // If paused, discard read bytes to drain the line buffer without writing or publishing
                if (session.paused.get()) {
                    continue
                }

                if (readBytes > 0) {
                    try {
                        writer.write(readBuffer, readBytes)
                        writer.flush()
                    } catch (t: Throwable) {
                        failAndCloseSession(session, "Audio processing failed: ${t.message}")
                        break
                    }

                    val currentWritten = writer.pcmBytesWritten
                    if (currentWritten - lastSyncedBytes >= 32_000L) {
                        val savedPos = raf.filePointer
                        raf.seek(0)
                        raf.write(pcm16MonoWavHeader(currentWritten, 16_000))
                        raf.fd.sync()
                        raf.seek(savedPos)
                        lastSyncedBytes = currentWritten
                        // Never publish Recording if paused occurred in-flight
                        if (session.capturing.get() && !session.paused.get() && activeSession === session) {
                            _sessions.value = MicSessionState.Recording(session.session.absolutePath, currentWritten, session.session.token)
                        }
                    }
                }
            }
        } finally {
            runCatching { writer.finish() }
            runCatching {
                val finalBytes = writer.pcmBytesWritten
                raf.seek(0)
                raf.write(pcm16MonoWavHeader(finalBytes, 16_000))
                raf.fd.sync()
            }
            session.closeResources()
            session.settled.set(true)
        }
    }

    override suspend fun pause(): MicPauseResult =
        synchronized(sessionLock) { activeSession?.session }?.let { pause(it) } ?: MicPauseResult.NotCapturing

    override suspend fun pause(session: MicSessionIdentity): MicPauseResult =
        synchronized(sessionLock) {
            val active = activeSession
            if (active == null || active.session != session || !active.capturing.get()) {
                return MicPauseResult.NotCapturing
            }
            if (active.paused.compareAndSet(false, true)) {
                _sessions.value = MicSessionState.Paused(session.absolutePath, active.pcmBytesWritten, session.token)
            }
            MicPauseResult.Ok
        }

    override suspend fun resume(): MicPauseResult =
        synchronized(sessionLock) { activeSession?.session }?.let { resume(it) } ?: MicPauseResult.NotCapturing

    override suspend fun resume(session: MicSessionIdentity): MicPauseResult =
        synchronized(sessionLock) {
            val active = activeSession
            if (active == null || active.session != session || !active.capturing.get()) {
                return MicPauseResult.NotCapturing
            }
            if (active.paused.compareAndSet(true, false)) {
                _sessions.value = MicSessionState.Recording(session.absolutePath, active.pcmBytesWritten, session.token)
            }
            MicPauseResult.Ok
        }

    override suspend fun addMarker(label: String): MicMarkerResult =
        synchronized(sessionLock) { activeSession?.session }?.let { addMarker(it, label) } ?: MicMarkerResult.NotCapturing

    override suspend fun addMarker(session: MicSessionIdentity, label: String): MicMarkerResult =
        synchronized(sessionLock) {
            val active = activeSession
            if (active == null || active.session != session || !active.capturing.get()) {
                return MicMarkerResult.NotCapturing
            }
            // Markers strictly follow output PCM frames written so far (independent of wall-clock jitter)
            val offsetMs = active.recordedOffsetMs
            val marker = MicMarker(offsetMs = offsetMs, label = label)
            synchronized(markersLock) {
                sessionMarkers.add(marker)
            }
            MicMarkerResult.Ok(marker)
        }

    override suspend fun stop(): MicStopResult =
        synchronized(sessionLock) { activeSession?.session }?.let { stop(it) } ?: MicStopResult.NotCapturing

    override suspend fun stop(session: MicSessionIdentity): MicStopResult = startMutex.withLock {
        val current = _sessions.value
        if (current is MicSessionState.Stopped && current.session == session) {
            return MicStopResult.Stopped(
                current.absolutePath,
                current.durationSeconds,
                current.sizeBytes,
                session,
            )
        }

        val active = synchronized(sessionLock) {
            activeSession?.takeIf { it.session == session && it.capturing.get() }
        } ?: return MicStopResult.NotCapturing

        // 1. Signal capture stop and unblock any pending read
        active.capturing.set(false)
        active.paused.set(false)
        runCatching { active.line.stop() }
        runCatching { active.line.close() }

        // 2. Bounded worker settlement (outside sessionLock)
        val worker = active.workerThread
        if (worker != null) {
            try {
                worker.join(5000)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        // 3. If worker did not terminate, RETAIN ownership: do not clear activeSession
        if (worker?.isAlive == true) {
            val failMsg = "Microphone worker settlement timed out after 5000ms"
            val failState = MicSessionState.Failed(failMsg, permissionRequired = false, session = session)
            _sessions.value = failState
            _failures.value = failMsg
            return MicStopResult.Failed(failMsg)
        }

        active.settled.set(true)

        // 4. File writes are settled. Finalize WAV header
        val recovered = try {
            WavWriter.recoverOrFinalize(session.absolutePath, 16_000)
        } catch (_: Exception) {
            null
        }

        if (recovered == null) {
            val failMsg = "Failed to finalize recording WAV header"
            val failState = MicSessionState.Failed(failMsg, permissionRequired = false, session = session)
            _sessions.value = failState
            _failures.value = failMsg
            active.closeResources()
            synchronized(sessionLock) { activeSession = null }
            return MicStopResult.Failed(failMsg)
        }

        // 5. Successful stop
        active.closeResources()
        synchronized(sessionLock) {
            activeSession = null
            val stopped = MicSessionState.Stopped(
                absolutePath = recovered.absolutePath,
                durationSeconds = recovered.durationSeconds,
                sizeBytes = recovered.sizeBytes,
                token = session.token,
            )
            _sessions.value = stopped
        }

        return MicStopResult.Stopped(
            absolutePath = recovered.absolutePath,
            durationSeconds = recovered.durationSeconds,
            sizeBytes = recovered.sizeBytes,
            session = session,
        )
    }
}
