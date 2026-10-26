package dev.velum.app.platform

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
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
        val bufSize = minBuf.coerceAtLeast(sampleRateHz / 5 * 2) // ≥100 ms
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
            while (running.get()) {
                val n = audioRecord.read(buf, 0, buf.size)
                if (n > 0) onPcm(buf.copyOf(n))
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
        } catch (_: IllegalStateException) {
        }
        record?.release()
        record = null
    }
}
