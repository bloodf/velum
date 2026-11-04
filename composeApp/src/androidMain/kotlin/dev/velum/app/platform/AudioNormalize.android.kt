package dev.velum.app.platform

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

actual class AudioNormalizer actual constructor() {

    actual suspend fun normalizeToWav(
        sourcePath: String,
        targetSampleRateHz: Int,
    ): NormalizedAudio {
        var outFile: File? = null
        try {
            return withContext(Dispatchers.IO) {
                val job = coroutineContext[Job]
                normalizeBlocking(sourcePath, targetSampleRateHz, { outFile = it }) { job?.ensureActive() }
            }
        } catch (c: CancellationException) {
            // withContext discards a result when the caller was cancelled just as the block finished.
            outFile?.delete()
            throw c
        }
    }

    private fun normalizeBlocking(
        sourcePath: String,
        targetSampleRateHz: Int,
        onOutput: (File) -> Unit,
        checkCancelled: () -> Unit,
    ): NormalizedAudio {
        val source = File(sourcePath)
        require(source.isFile && source.length() > 0L) { "Missing import: $sourcePath" }
        val hash = sha256File(sourcePath)
        val mime = guessMime(source.name)
        // The only copy of an imported recording: it lives with the other recordings, never in the evictable cache.
        // No row points here until the import succeeds; a failed or duplicate import deletes it.
        val outDir = File(recordingsDirectoryPath()).apply { mkdirs() }
        val name = normalizedFileName(source.nameWithoutExtension, targetSampleRateHz, hash).removeSuffix(".wav")
        val outFile = File(outDir, "import_${name}_${java.util.UUID.randomUUID()}.wav")
        sweepStalePartFiles(outDir, System.currentTimeMillis() - STALE_PART_AGE_MS)

        // Fast path: PCM16 mono WAV at the target rate is streamed through, never read whole (#319). The header is
        // read before anything is created, so a failure here can never touch an earlier import of the same name.
        val fast = if (source.extension.equals("wav", true) || source.extension.equals("wave", true)) {
            readWavHeader(source)
        } else {
            null
        }
        val usesFastPath = fast != null && fast.sampleRateHz == targetSampleRateHz && fast.channels == 1
        // The WAV is written under a name only this call uses and renamed on success, so two imports that map to
        // the same output name (a retry right after a cancel, two copies of one file name) never delete or
        // overwrite each other's half-written file (#319).
        val temp = File(outDir, "${outFile.name}.${System.nanoTime()}.part")
        onOutput(temp) // reported for a late cancel, and deleted on any failure
        try {
            val pcmBytes = MonoPcmWavWriter(temp, targetSampleRateHz).use { writer ->
                if (usesFastPath && fast != null) {
                    writer.setFormat(fast.sampleRateHz, fast.channels)
                    streamWavData(source, fast, writer, checkCancelled)
                } else {
                    decodeWithMediaCodec(source, targetSampleRateHz, writer, checkCancelled)
                }
                writer.finish()
                writer.pcmBytesWritten
            }
            checkCancelled()
            // Of the normalized audio, so the same audio in another container is recognised (#327).
            val contentHash = sha256File(temp.absolutePath)
            // Durable before it is published: the row that will point at it is written right after.
            java.io.RandomAccessFile(temp, "rw").use { it.fd.sync() }
            java.nio.file.Files.move(
                temp.toPath(), outFile.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            )
            syncDirectory(outFile.parentFile)
            onOutput(outFile) // the temp name is gone; a cancel that lands after this removes the final file
            val durationMs = (pcmBytes / 2.0 / targetSampleRateHz * 1000.0).toLong()
            return NormalizedAudio(
                wavPath = outFile.absolutePath,
                sampleRateHz = targetSampleRateHz,
                durationMs = if (usesFastPath) {
                    durationMs
                } else {
                    durationMs.coerceAtLeast(durationHintMs(sourcePath))
                },
                sourceMime = mime,
                contentSha256 = contentHash,
            )
        } catch (t: Throwable) {
            temp.delete() // never leave a half-written WAV that a later import could trust
            throw t
        }
    }

    private fun durationHintMs(path: String): Long {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(path)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Throwable) {
            0L
        } finally {
            releaseAll({ r.release() }) // also when setDataSource or extractMetadata throws
        }
    }

    private fun decodeWithMediaCodec(
        source: File,
        targetSampleRateHz: Int,
        writer: MonoPcmWavWriter,
        checkCancelled: () -> Unit,
    ) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var started = false
        try {
            extractor.setDataSource(source.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull { i ->
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                mime.startsWith("audio/")
            } ?: error("No audio track in ${source.name}")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)
                ?: error("Missing MIME on audio track")
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
            started = true

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var sourceRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else {
                targetSampleRateHz
            }
            var channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else {
                1
            }
            writer.setFormat(sourceRate, channels)

            while (!outputDone) {
                checkCancelled() // an import of a long file must stop when its job is cancelled
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buffer = codec.getInputBuffer(inIndex)!!
                        buffer.clear()
                        val sampleSize = extractor.readSampleData(buffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(
                                inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(
                                inIndex, 0, sampleSize, extractor.sampleTime, 0,
                            )
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFormat = codec.outputFormat
                        if (outFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            sourceRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        }
                        if (outFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            channels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                        writer.setFormat(sourceRate, channels)
                    }
                    outIndex >= 0 -> {
                        val outBuf = codec.getOutputBuffer(outIndex)!!
                        if (info.size > 0) {
                            val chunk = ByteArray(info.size)
                            outBuf.position(info.offset)
                            outBuf.get(chunk)
                            writer.write(chunk) // converted and written now; nothing accumulates
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                }
            }
        } finally {
            // Every exit path releases the hardware codec and the extractor (#319).
            val c = codec
            releaseAll(
                { if (started) c?.stop() },
                { c?.release() },
                { extractor.release() },
            )
        }
    }

    /** Copies the data chunk of a mono WAV into [writer] in slices. */
    private fun streamWavData(source: File, header: WavHeader, writer: MonoPcmWavWriter, checkCancelled: () -> Unit) {
        RandomAccessFile(source, "r").use { raf ->
            raf.seek(header.dataOffset)
            val buf = ByteArray(WAV_SLICE_BYTES)
            var left = header.dataSize
            while (left > 0) {
                checkCancelled()
                val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) break
                writer.write(buf, n)
                left -= n
            }
        }
    }
}

private const val WAV_SLICE_BYTES = 256 * 1024

/** A temp `.part` older than this belongs to a process that died; a live normalize keeps writing to its own. */
internal const val STALE_PART_AGE_MS = 30L * 60L * 1000L

internal fun normalizedFileName(baseName: String, targetRateHz: Int, sourceSha256: String): String =
    "${baseName}_${targetRateHz}_${sourceSha256.take(12)}.wav"

private val PART_FILE_NAME = Regex(""".+\.wav\.\d+\.part""")

/** Deletes `*.wav.<n>.part` files in [dir] last modified before [olderThanEpochMs]; returns how many. */
internal fun sweepStalePartFiles(dir: File, olderThanEpochMs: Long): Int =
    dir.listFiles().orEmpty().count { f ->
        f.isFile && PART_FILE_NAME.matches(f.name) && f.lastModified() < olderThanEpochMs && f.delete()
    }

internal data class WavHeader(
    val sampleRateHz: Int,
    val channels: Int,
    val dataOffset: Long,
    val dataSize: Long,
)

/**
 * Walks the RIFF chunks with seeks (no full read). Null when the file is not RIFF/WAVE, has no data chunk or
 * is not 16-bit PCM, so the decoder gets it instead of the import failing (#319). The caller streams only mono at the
 * target rate.
 */
internal fun readWavHeader(file: File): WavHeader? = RandomAccessFile(file, "r").use { raf ->
    val length = raf.length()
    if (length < 12) return null
    val head = ByteArray(12).also { raf.readFully(it) }
    if (head.decodeToString(0, 4) != "RIFF" || head.decodeToString(8, 12) != "WAVE") return null
    var offset = 12L
    var sampleRate = 16_000
    var channels = 1
    var bits = 16
    var audioFormat = 1
    var subFormat = 1 // only read for WAVE_FORMAT_EXTENSIBLE with a full fmt chunk
    var dataOffset = -1L
    var dataSize = 0L
    val chunk = ByteArray(8)
    while (offset + 8 <= length) {
        raf.seek(offset)
        raf.readFully(chunk)
        val id = chunk.decodeToString(0, 4)
        val size = ByteBuffer.wrap(chunk, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFF_FFFFL
        when (id) {
            "fmt " -> if (size >= 16) {
                // 40 bytes cover the WAVE_FORMAT_EXTENSIBLE sub-format code when the chunk has it.
                val fmt = ByteArray(if (size >= 40) 40 else 16).also { raf.readFully(it) }
                if (fmt.size == 40) {
                    subFormat = (fmt[24].toInt() and 0xFF) or ((fmt[25].toInt() and 0xFF) shl 8)
                }
                val bb = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN)
                audioFormat = bb.short.toInt() and 0xFFFF
                channels = bb.short.toInt() and 0xFFFF
                sampleRate = bb.int
                bb.int // byte rate
                bb.short // block align
                bits = bb.short.toInt() and 0xFFFF
            }
            "data" -> {
                dataOffset = offset + 8
                dataSize = minOf(size, length - dataOffset)
            }
        }
        offset += 8 + size + (size and 1L)
    }
    // No data chunk: nothing to stream here; the decoder gets the file and reports what is wrong with it.
    // PCM (1), or WAVE_FORMAT_EXTENSIBLE (0xFFFE) whose sub-format is PCM; anything else (float, ADPCM, A-law,
    // extensible float) goes to the decoder.
    val pcm = audioFormat == 1 || (audioFormat == 0xFFFE && subFormat == 1)
    if (dataOffset < 0 || bits != 16 || !pcm) return null
    WavHeader(sampleRate, channels, dataOffset, dataSize)
}

private fun guessMime(name: String): String? {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "mp3" -> "audio/mpeg"
        "m4a", "mp4", "m4b" -> "audio/mp4"
        "aac" -> "audio/aac"
        "wav", "wave" -> "audio/wav"
        "ogg" -> "audio/ogg"
        "opus" -> "audio/opus"
        "flac" -> "audio/flac"
        "amr" -> "audio/amr"
        "3gp", "3gpp" -> "audio/3gpp"
        "webm" -> "audio/webm"
        else -> null
    }
}
