package dev.velum.app.platform

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

actual fun createLivePcmCapture(): LivePcmCapture? = AndroidMicPcmCapture()

private class AndroidMicPcmCapture : LivePcmCapture {
    override val sampleRateHz: Int = 16_000

    private val running = AtomicBoolean(false)
    private var record: AudioRecord? = null
    private var worker: Thread? = null

    override fun start(onPcm: (ByteArray) -> Unit): Boolean {
        if (running.get()) return true
        val ctx = ShareTextHolder.context ?: return false
        val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return false

        val minBuf = AudioRecord.getMinBufferSize(
            sampleRateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) return false
        val bufSize = minBuf.coerceAtLeast(sampleRateHz / 5 * 2)
        val audioRecord = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRateHz,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize,
            )
        } catch (_: SecurityException) {
            return false
        }
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            return false
        }
        record = audioRecord
        running.set(true)
        audioRecord.startRecording()
        worker = thread(name = "velum-mic-pcm", isDaemon = true) {
            val buf = ByteArray(bufSize)
            try {
                while (running.get()) {
                    val n = audioRecord.read(buf, 0, buf.size)
                    if (n > 0) {
                        onPcm(buf.copyOf(n))
                    } else if (n < 0) {
                        Log.e(TAG, "AudioRecord.read error code=$n")
                        break
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Live PCM capture failed", t)
                if (t is IOException) {
                    running.set(false)
                }
            } finally {
                running.set(false)
            }
        }
        return true
    }

    override fun stop() {
        running.set(false)
        worker?.join(500)
        worker = null
        try {
            record?.stop()
        } catch (t: IllegalStateException) {
            Log.e(TAG, "AudioRecord.stop failed", t)
        }
        record?.release()
        record = null
    }

    companion object {
        private const val TAG = "VelumLivePcm"
    }
}
