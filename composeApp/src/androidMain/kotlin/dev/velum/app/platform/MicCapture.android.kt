package dev.velum.app.platform

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

private var micAppContext: android.content.Context? = null

fun installMicCaptureContext(context: android.content.Context) {
    micAppContext = context.applicationContext
}

actual fun createMicCapture(): MicCapture = AndroidMicCapture { micAppContext }

private class AndroidMicCapture(
    private val contextProvider: () -> android.content.Context?,
) : MicCapture {
    override val isSupported: Boolean = true

    private val capturing = AtomicBoolean(false)
    @Volatile private var recordThread: Thread? = null
    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile private var outputFile: File? = null
    @Volatile private var bytesWritten: Long = 0
    @Volatile private var startedAtMs: Long = 0

    override val isCapturing: Boolean
        get() = capturing.get()

    @Volatile
    override var onPcmChunk: ((ByteArray) -> Unit)? = null

    override fun start(absolutePath: String): MicStartResult {
        val context = contextProvider()
            ?: return MicStartResult.Failed("No Android context")
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return MicStartResult.PermissionRequired
        if (!capturing.compareAndSet(false, true)) {
            return MicStartResult.Failed("Already capturing")
        }

        val file = File(absolutePath)
        file.parentFile?.mkdirs()
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            capturing.set(false)
            return MicStartResult.Failed("AudioRecord buffer unavailable ($minBuf)")
        }
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2,
            )
        } catch (t: Throwable) {
            capturing.set(false)
            return MicStartResult.Failed(t.message ?: t.toString())
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            capturing.set(false)
            return MicStartResult.Failed("AudioRecord failed to initialize")
        }

        outputFile = file
        bytesWritten = 0
        startedAtMs = System.currentTimeMillis()
        audioRecord = recorder

        try {
            RandomAccessFile(file, "rw").use { raf ->
                raf.setLength(0)
                writeWavHeader(raf, dataSize = 0)
            }
            recorder.startRecording()
        } catch (t: Throwable) {
            recorder.release()
            audioRecord = null
            capturing.set(false)
            return MicStartResult.Failed(t.message ?: t.toString())
        }

        recordThread = thread(name = "velum-mic-capture", isDaemon = true) {
            val buf = ByteArray(minBuf)
            try {
                RandomAccessFile(file, "rw").use { raf ->
                    raf.seek(44)
                    while (capturing.get()) {
                        val n = recorder.read(buf, 0, buf.size)
                        if (n > 0) {
                            raf.write(buf, 0, n)
                            bytesWritten += n.toLong()
                            onPcmChunk?.invoke(buf.copyOf(n))
                        } else if (n < 0) {
                            break
                        }
                    }
                    raf.seek(0)
                    writeWavHeader(raf, dataSize = bytesWritten)
                    raf.fd.sync()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Mic capture write failed", t)
                capturing.set(false)
            } finally {
                try {
                    recorder.stop()
                } catch (t: Throwable) {
                    Log.e(TAG, "AudioRecord.stop failed", t)
                }
                recorder.release()
                if (audioRecord === recorder) audioRecord = null
            }
        }
        return MicStartResult.Started
    }

    override fun stop(): MicStopResult {
        if (!capturing.getAndSet(false)) return MicStopResult.NotCapturing
        val file = outputFile
        val thread = recordThread
        recordThread = null
        try {
            thread?.join(5_000)
        } catch (_: InterruptedException) {
        }
        val path = file?.absolutePath
            ?: return MicStopResult.Failed("Missing output file")
        val size = file.length()
        val duration = ((System.currentTimeMillis() - startedAtMs) / 1000L).coerceAtLeast(0)
        outputFile = null
        return MicStopResult.Stopped(
            absolutePath = path,
            durationSeconds = duration,
            sizeBytes = size,
        )
    }

    private fun writeWavHeader(raf: RandomAccessFile, dataSize: Long) {
        val sampleRate = SAMPLE_RATE_HZ
        val channels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val chunkSize = 36L + dataSize
        raf.writeBytes("RIFF")
        writeIntLE(raf, chunkSize.toInt())
        raf.writeBytes("WAVE")
        raf.writeBytes("fmt ")
        writeIntLE(raf, 16)
        writeShortLE(raf, 1) // PCM
        writeShortLE(raf, channels.toShort())
        writeIntLE(raf, sampleRate)
        writeIntLE(raf, byteRate)
        writeShortLE(raf, blockAlign.toShort())
        writeShortLE(raf, bitsPerSample.toShort())
        raf.writeBytes("data")
        writeIntLE(raf, dataSize.toInt())
    }

    private fun writeIntLE(raf: RandomAccessFile, value: Int) {
        raf.write(value and 0xFF)
        raf.write((value shr 8) and 0xFF)
        raf.write((value shr 16) and 0xFF)
        raf.write((value shr 24) and 0xFF)
    }

    private fun writeShortLE(raf: RandomAccessFile, value: Short) {
        val v = value.toInt()
        raf.write(v and 0xFF)
        raf.write((v shr 8) and 0xFF)
    }

    companion object {
        const val SAMPLE_RATE_HZ = 16_000
        private const val TAG = "VelumMicCapture"
    }
}
