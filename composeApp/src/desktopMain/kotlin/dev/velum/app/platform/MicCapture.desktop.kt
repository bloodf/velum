package dev.velum.app.platform

import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.sound.sampled.LineUnavailableException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

actual fun createMicCapture(): MicCapture = DesktopMicCapture()

internal class DesktopMicCapture(
    private val lineFactory: DesktopAudioLineFactory = JavaSoundAudioLineFactory,
    private val timeProvider: () -> Long = { System.nanoTime() / 1_000_000L },
) : MicCapture {

    private val startMutex = Mutex()
    private val sessionSequence = AtomicLong(0)
    private val sessionLock = Any()
    private val markersLock = Any()

    private val _sessions = MutableStateFlow<MicSessionState>(MicSessionState.Idle)
    override val sessions: StateFlow<MicSessionState> = _sessions.asStateFlow()

    private val _failures = MutableStateFlow<String?>(null)
    override val failures: StateFlow<String?> = _failures.asStateFlow()

    private val capturing = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)

    private var currentSession: MicSessionIdentity? = null
    private val sessionMarkers = ArrayList<MicMarker>()

    private var startedAtElapsedMs: Long = 0L
    private var pausedAccumulatedMs: Long = 0L
    private var pauseStartedElapsedMs: Long = 0L

    @Volatile
    private var pcmListener: ((ByteArray) -> Unit)? = null

    private var workerThread: Thread? = null
    private var activeLine: DesktopAudioLine? = null
    private var outputFile: File? = null

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
        get() = pcmListener
        set(value) { pcmListener = value }

    override fun attachPcm(session: MicSessionIdentity, listener: (ByteArray) -> Unit): Boolean {
        if (_sessions.value.session != session || !isCapturing) return false
        pcmListener = listener
        return true
    }

    override fun detachPcm(session: MicSessionIdentity, listener: (ByteArray) -> Unit) {
        if (_sessions.value.session == session && pcmListener === listener) {
            pcmListener = null
        }
    }

    override suspend fun start(
        absolutePath: String,
        inputSource: MicInputSource,
    ): MicStartResult = startMutex.withLock {
        if (isCapturing) return MicStartResult.Failed("Recording already in progress")

        val file = File(absolutePath)
        file.parentFile?.mkdirs()
        if (file.exists()) return MicStartResult.Failed("Recording output already exists: $absolutePath")

        val token = sessionSequence.incrementAndGet()
        val session = MicSessionIdentity(token, absolutePath)

        val line = try {
            lineFactory.createLine(inputSource).also { it.open() }
        } catch (e: SecurityException) {
            val fail = MicSessionState.Failed(
                message = "Microphone permission denied: ${e.message ?: "permission required"}",
                permissionRequired = true,
                session = session,
            )
            _sessions.value = fail
            _failures.value = fail.message
            return MicStartResult.PermissionRequired
        } catch (e: LineUnavailableException) {
            val isExplicitPerm = e.message?.contains("permission", ignoreCase = true) == true
            val fail = MicSessionState.Failed(
                message = "Microphone line unavailable: ${e.message ?: "device busy or unavailable"}",
                permissionRequired = isExplicitPerm,
                session = session,
            )
            _sessions.value = fail
            _failures.value = fail.message
            return if (isExplicitPerm) MicStartResult.PermissionRequired else MicStartResult.Failed(fail.message)
        } catch (e: Exception) {
            val isExplicitPerm = e.message?.contains("permission", ignoreCase = true) == true
            val fail = MicSessionState.Failed(
                message = "Microphone initialization failed: ${e.message ?: "unknown error"}",
                permissionRequired = isExplicitPerm,
                session = session,
            )
            _sessions.value = fail
            _failures.value = fail.message
            return if (isExplicitPerm) MicStartResult.PermissionRequired else MicStartResult.Failed(fail.message)
        }

        synchronized(sessionLock) {
            currentSession = session
            activeLine = line
            outputFile = file
            startedAtElapsedMs = timeProvider()
            pausedAccumulatedMs = 0L
            pauseStartedElapsedMs = 0L
            paused.set(false)
            capturing.set(true)
            _failures.value = null
            _sessions.value = MicSessionState.Recording(absolutePath, 0L, token)
        }
        synchronized(markersLock) {
            sessionMarkers.clear()
        }

        val thread = Thread({
            runCaptureLoop(session, line, file)
        }, "velum-desktop-mic").apply {
            isDaemon = true
            start()
        }
        synchronized(sessionLock) {
            workerThread = thread
        }

        return MicStartResult.Started(session)
    }

    private fun runCaptureLoop(session: MicSessionIdentity, line: DesktopAudioLine, file: File) {
        val token = session.token
        var raf: RandomAccessFile? = null
        var resampler: MonoPcmWavWriter? = null
        try {
            raf = RandomAccessFile(file, "rw").apply {
                seek(0)
                write(pcm16MonoWavHeader(0, 16_000))
                seek(44)
            }
            val rafHandle = raf

            val rafStream = object : java.io.OutputStream() {
                override fun write(b: Int) {
                    rafHandle.write(b)
                }
                override fun write(b: ByteArray, off: Int, len: Int) {
                    rafHandle.write(b, off, len)
                }
                override fun flush() {}
                override fun close() {}
            }

            val chunkSink = PcmChunkSink(
                fileOutputStream = rafStream,
                onChunk = { pcmChunk ->
                    if (capturing.get() && currentSession?.token == token) {
                        pcmListener?.invoke(pcmChunk)
                    }
                },
            )

            val writer = MonoPcmWavWriter(
                file = null,
                targetRate = 16_000,
                sink = chunkSink,
                writeHeaderPlaceholder = false,
            ).apply {
                setFormat(line.sampleRateHz, line.channels)
            }
            resampler = writer

            val bufSize = line.bufferSizeBytes.coerceAtLeast(4096)
            val readBuffer = ByteArray(bufSize)
            var lastSyncedBytes = 0L

            line.start()

            while (capturing.get() && currentSession?.token == token) {
                if (paused.get()) {
                    try {
                        Thread.sleep(50)
                    } catch (_: InterruptedException) {
                        break
                    }
                    continue
                }

                val readBytes = line.read(readBuffer, 0, readBuffer.size)
                if (!capturing.get() || currentSession?.token != token) break
                if (readBytes < 0) {
                    break
                }
                if (readBytes > 0) {
                    writer.write(readBuffer, readBytes)
                    writer.flush()

                    val currentWritten = writer.pcmBytesWritten
                    if (currentWritten - lastSyncedBytes >= 32_000L) {
                        val savedPos = rafHandle.filePointer
                        rafHandle.seek(0)
                        rafHandle.write(pcm16MonoWavHeader(currentWritten, 16_000))
                        rafHandle.fd.sync()
                        rafHandle.seek(savedPos)
                        lastSyncedBytes = currentWritten
                        if (capturing.get() && currentSession?.token == token) {
                            _sessions.value = MicSessionState.Recording(session.absolutePath, currentWritten, token)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (capturing.get() && currentSession?.token == token) {
                val fail = MicSessionState.Failed("Microphone capture failed: ${e.message}", false, session)
                _sessions.value = fail
                _failures.value = fail.message
            }
        } finally {
            // Finish resampler and flush any trailing samples to file
            runCatching { resampler?.finish() }
            // Write final header checkpoint to raf before closing
            runCatching {
                raf?.let { r ->
                    val finalBytes = resampler?.pcmBytesWritten ?: 0L
                    r.seek(0)
                    r.write(pcm16MonoWavHeader(finalBytes, 16_000))
                    r.fd.sync()
                }
            }
            runCatching { raf?.close() }
            runCatching { line.stop() }
            runCatching { line.close() }
        }
    }

    override suspend fun pause(): MicPauseResult =
        currentSession?.let { pause(it) } ?: MicPauseResult.NotCapturing

    override suspend fun pause(session: MicSessionIdentity): MicPauseResult {
        synchronized(sessionLock) {
            if (currentSession != session || !capturing.get()) {
                return MicPauseResult.NotCapturing
            }
            if (paused.compareAndSet(false, true)) {
                pauseStartedElapsedMs = timeProvider()
                val current = _sessions.value
                val written = (current as? MicSessionState.Recording)?.bytesWritten ?: 0L
                _sessions.value = MicSessionState.Paused(session.absolutePath, written, session.token)
            }
        }
        return MicPauseResult.Ok
    }

    override suspend fun resume(): MicPauseResult =
        currentSession?.let { resume(it) } ?: MicPauseResult.NotCapturing

    override suspend fun resume(session: MicSessionIdentity): MicPauseResult {
        synchronized(sessionLock) {
            if (currentSession != session || !capturing.get()) {
                return MicPauseResult.NotCapturing
            }
            if (paused.compareAndSet(true, false)) {
                if (pauseStartedElapsedMs > 0) {
                    pausedAccumulatedMs += (timeProvider() - pauseStartedElapsedMs).coerceAtLeast(0L)
                    pauseStartedElapsedMs = 0L
                }
                val current = _sessions.value
                val written = (current as? MicSessionState.Paused)?.bytesWritten ?: 0L
                _sessions.value = MicSessionState.Recording(session.absolutePath, written, session.token)
            }
        }
        return MicPauseResult.Ok
    }

    override suspend fun addMarker(label: String): MicMarkerResult =
        currentSession?.let { addMarker(it, label) } ?: MicMarkerResult.NotCapturing

    override suspend fun addMarker(session: MicSessionIdentity, label: String): MicMarkerResult {
        synchronized(sessionLock) {
            if (currentSession != session || !capturing.get()) {
                return MicMarkerResult.NotCapturing
            }
            val now = timeProvider()
            val pausedExtra = if (paused.get() && pauseStartedElapsedMs > 0) {
                (now - pauseStartedElapsedMs).coerceAtLeast(0L)
            } else {
                0L
            }
            val offsetMs = (now - startedAtElapsedMs - pausedAccumulatedMs - pausedExtra).coerceAtLeast(0L)
            val marker = MicMarker(offsetMs = offsetMs, label = label)
            synchronized(markersLock) {
                sessionMarkers.add(marker)
            }
            return MicMarkerResult.Ok(marker)
        }
    }

    override suspend fun stop(): MicStopResult =
        currentSession?.let { stop(it) } ?: MicStopResult.NotCapturing

    override suspend fun stop(session: MicSessionIdentity): MicStopResult {
        val current = _sessions.value
        if (current is MicSessionState.Stopped && current.session == session) {
            return MicStopResult.Stopped(
                current.absolutePath,
                current.durationSeconds,
                current.sizeBytes,
                session,
            )
        }

        val worker: Thread?
        val line: DesktopAudioLine?

        synchronized(sessionLock) {
            if (currentSession != session || !capturing.get()) {
                return MicStopResult.NotCapturing
            }
            capturing.set(false)
            paused.set(false)
            worker = workerThread
            workerThread = null
            line = activeLine
            activeLine = null
        }

        // 1. Unblock any pending line read before waiting for worker settlement
        line?.stop()
        line?.close()

        // 2. Bounded worker settlement (without holding sessionLock)
        try {
            worker?.join(5000)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        // 3. Ensure worker did not time out
        if (worker?.isAlive == true) {
            val fail = MicSessionState.Failed("Microphone worker settlement timed out", false, session)
            _sessions.value = fail
            _failures.value = fail.message
            return MicStopResult.Failed(fail.message)
        }

        // 4. File writes are guaranteed finished. Finalize WAV header via WavWriter.recoverOrFinalize
        val path = session.absolutePath
        val recovered = WavWriter.recoverOrFinalize(path, 16_000)
        if (recovered == null) {
            val fail = MicSessionState.Failed("Failed to finalize recording WAV", false, session)
            _sessions.value = fail
            _failures.value = fail.message
            return MicStopResult.Failed(fail.message)
        }

        synchronized(sessionLock) {
            val stopped = MicSessionState.Stopped(
                absolutePath = recovered.absolutePath,
                durationSeconds = recovered.durationSeconds,
                sizeBytes = recovered.sizeBytes,
                token = session.token,
            )
            _sessions.value = stopped
            pcmListener = null
            currentSession = null
            outputFile = null
        }

        return MicStopResult.Stopped(
            absolutePath = recovered.absolutePath,
            durationSeconds = recovered.durationSeconds,
            sizeBytes = recovered.sizeBytes,
            session = session,
        )
    }
}
