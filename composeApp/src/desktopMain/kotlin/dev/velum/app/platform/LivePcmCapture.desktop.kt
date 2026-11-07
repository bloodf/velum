package dev.velum.app.platform

import java.util.concurrent.atomic.AtomicBoolean

actual fun createLivePcmCapture(): LivePcmCapture? = DesktopLivePcmCapture()

internal class DesktopLivePcmCapture(
    private val lineFactory: DesktopAudioLineFactory = JavaSoundAudioLineFactory,
) : LivePcmCapture {
    override val sampleRateHz: Int = 16_000

    @Volatile private var running = AtomicBoolean(false)
    private var line: DesktopAudioLine? = null
    private var worker: Thread? = null

    @Synchronized override fun start(onPcm: (ByteArray) -> Unit): Boolean {
        if (running.get()) return true

        val audioLine = try {
            lineFactory.createLine(MicInputSource.Mic)
        } catch (_: Exception) {
            return false
        }
        try {
            audioLine.open()
            audioLine.start()
        } catch (_: Exception) {
            runCatching { audioLine.close() }
            return false
        }

        line = audioLine
        val running = AtomicBoolean(true)
        this.running = running

        val sink = PcmChunkSink(
            fileOutputStream = null,
            onChunk = { chunk ->
                if (running.get()) {
                    try {
                        onPcm(chunk)
                    } catch (_: Throwable) {
                        running.set(false)
                        runCatching { audioLine.stop() }
                        runCatching { audioLine.close() }
                    }
                }
            },
        )
        val resampler = MonoPcmWavWriter(
            file = null,
            targetRate = sampleRateHz,
            sink = sink,
            writeHeaderPlaceholder = false,
        ).apply {
            setFormat(audioLine.sampleRateHz, audioLine.channels)
        }

        val thread = Thread({
            val bufSize = audioLine.bufferSizeBytes.coerceAtLeast(4096)
            val buf = ByteArray(bufSize)
            try {
                while (running.get()) {
                    val n = audioLine.read(buf, 0, buf.size)
                    if (!running.get()) break
                    if (n < 0) {
                        break
                    }
                    if (n > 0) {
                        resampler.write(buf, n)
                        resampler.flush()
                    }
                }
            } catch (_: Exception) {
            } finally {
                running.set(false)
                runCatching { resampler.finish() }
                runCatching { audioLine.stop() }
                runCatching { audioLine.close() }
            }
        }, "velum-desktop-live-pcm").apply {
            isDaemon = true
        }

        worker = thread
        thread.start()
        return true
    }

    @Synchronized override fun stop() {
        if (!running.getAndSet(false)) return
        val l = line
        line = null
        val w = worker
        worker = null
        l?.stop()
        l?.close()
        try {
            w?.join(500)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
