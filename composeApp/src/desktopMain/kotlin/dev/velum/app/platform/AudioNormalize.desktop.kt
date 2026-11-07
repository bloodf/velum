package dev.velum.app.platform

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Desktop normalizer: WAV normalization to PCM16 LE mono at targetSampleRateHz,
 * Ogg/Opus passthrough, and rejection of unsupported compressed formats.
 */
actual class AudioNormalizer(
    private val outputDirectoryProvider: () -> File = { File(recordingsDirectoryPath()) },
) {
    actual constructor() : this({ File(recordingsDirectoryPath()) })

    constructor(outputDir: File) : this({ outputDir })

    actual suspend fun normalizeToWav(
        sourcePath: String,
        targetSampleRateHz: Int,
    ): NormalizedAudio {
        var ownedOutput: File? = null
        try {
            return withContext(Dispatchers.IO) {
                val job = coroutineContext[Job]
                normalizeBlocking(
                    sourcePath = sourcePath,
                    targetSampleRateHz = targetSampleRateHz,
                    onOutput = { ownedOutput = it },
                    checkCancelled = { job?.ensureActive() },
                )
            }
        } catch (c: CancellationException) {
            ownedOutput?.delete()
            throw c
        } catch (t: Throwable) {
            ownedOutput?.delete()
            throw t
        }
    }

    private fun normalizeBlocking(
        sourcePath: String,
        targetSampleRateHz: Int,
        onOutput: (File) -> Unit,
        checkCancelled: () -> Unit,
    ): NormalizedAudio {
        val source = File(sourcePath)
        require(source.isFile && source.length() > 0L) { "Missing or empty import: $sourcePath" }
        val outDir = outputDirectoryProvider().apply { mkdirs() }
        val outputId = UUID.randomUUID()
        val name = source.name.lowercase()

        when {
            name.endsWith(".wav") || name.endsWith(".wave") -> {
                val wavInfo = readWavInfo(source)
                val outFile = File(outDir, "import_$outputId.wav")
                val temp = File(outDir, "${outFile.name}.${System.nanoTime()}.part")
                onOutput(temp)

                try {
                    val pcmBytes = MonoPcmWavWriter(temp, targetSampleRateHz).use { writer ->
                        writer.setFormat(wavInfo.sampleRateHz, wavInfo.channels)
                        streamWavPcm(source, wavInfo, writer, checkCancelled)
                        writer.finish()
                        writer.pcmBytesWritten
                    }
                    checkCancelled()

                    val contentHash = sha256File(temp.absolutePath)
                    try {
                        Files.move(temp.toPath(), outFile.toPath(), StandardCopyOption.ATOMIC_MOVE)
                    } catch (_: AtomicMoveNotSupportedException) {
                        Files.move(temp.toPath(), outFile.toPath())
                    }
                    onOutput(outFile)

                    val durationMs = (pcmBytes / 2.0 / targetSampleRateHz * 1000.0).toLong()
                    return NormalizedAudio(
                        wavPath = outFile.absolutePath,
                        sampleRateHz = targetSampleRateHz,
                        durationMs = durationMs,
                        sourceMime = "audio/wav",
                        contentSha256 = contentHash,
                    )
                } catch (t: Throwable) {
                    temp.delete()
                    throw t
                }
            }
            name.endsWith(".ogg") || name.endsWith(".opus") -> {
                val dest = File(outDir, "import_$outputId.${source.extension.lowercase()}")
                val temp = File(outDir, "${dest.name}.${System.nanoTime()}.part")
                onOutput(temp)

                try {
                    streamCopy(source, temp, checkCancelled)
                    checkCancelled()

                    val contentHash = sha256File(temp.absolutePath)
                    try {
                        Files.move(temp.toPath(), dest.toPath(), StandardCopyOption.ATOMIC_MOVE)
                    } catch (_: AtomicMoveNotSupportedException) {
                        Files.move(temp.toPath(), dest.toPath())
                    }
                    onOutput(dest)

                    return NormalizedAudio(
                        wavPath = dest.absolutePath,
                        sampleRateHz = targetSampleRateHz,
                        durationMs = 0L,
                        sourceMime = "audio/ogg",
                        contentSha256 = contentHash,
                    )
                } catch (t: Throwable) {
                    temp.delete()
                    throw t
                }
            }
            else -> error(
                "Desktop build cannot decode ${source.extension}; use Android for mp3/m4a/flac/amr/webm or convert to WAV first.",
            )
        }
    }
}

internal data class WavInfo(
    val audioFormat: Int,
    val channels: Int,
    val sampleRateHz: Int,
    val bitsPerSample: Int,
    val dataOffset: Long,
    val dataSize: Long,
)

private const val WAV_SLICE_BYTES = 64 * 1024

internal fun readWavInfo(file: File): WavInfo = RandomAccessFile(file, "r").use { raf ->
    val length = raf.length()
    if (length < 12) throw IOException("Truncated WAV: file size ($length bytes) is less than RIFF header")
    val riffHeader = ByteArray(12).also { raf.readFully(it) }
    val riffId = riffHeader.decodeToString(0, 4)
    if (riffId != "RIFF") throw IOException("Not a valid RIFF file: found '$riffId'")
    val riffSize = ByteBuffer.wrap(riffHeader, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFF_FFFFL
    if (riffSize < 4) throw IOException("Malformed RIFF size: $riffSize")
    val waveId = riffHeader.decodeToString(8, 12)
    if (waveId != "WAVE") throw IOException("Not a WAVE file: found '$waveId'")

    var offset = 12L
    var fmtFound = false
    var audioFormat = 0
    var channels = 0
    var sampleRate = 0
    var bitsPerSample = 0
    var dataOffset = -1L
    var dataSize = 0L

    val chunkHeader = ByteArray(8)
    while (offset < length) {
        if (offset + 8 > length) {
            throw IOException("Truncated WAV: incomplete chunk header at offset $offset")
        }
        raf.seek(offset)
        raf.readFully(chunkHeader)
        val id = chunkHeader.decodeToString(0, 4)
        val chunkSize = ByteBuffer.wrap(chunkHeader, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFF_FFFFL
        val chunkDataOffset = offset + 8
        if (chunkDataOffset + chunkSize > length) {
            throw IOException("Truncated WAV: chunk '$id' claims size $chunkSize but file ends at $length")
        }

        when (id) {
            "fmt " -> {
                if (chunkSize < 16) {
                    throw IOException("Malformed fmt chunk: size $chunkSize is less than 16")
                }
                val fmtBytes = ByteArray(if (chunkSize >= 40) 40 else chunkSize.toInt()).also { raf.readFully(it) }
                val bb = ByteBuffer.wrap(fmtBytes).order(ByteOrder.LITTLE_ENDIAN)
                audioFormat = bb.short.toInt() and 0xFFFF
                channels = bb.short.toInt() and 0xFFFF
                sampleRate = bb.int
                bb.int // byte rate
                bb.short // block align
                bitsPerSample = bb.short.toInt() and 0xFFFF

                var subFormat = 1
                if (audioFormat == 0xFFFE) {
                    if (chunkSize < 40) {
                        throw IOException("Malformed extensible fmt chunk: size $chunkSize is less than 40")
                    }
                    subFormat = (fmtBytes[24].toInt() and 0xFF) or ((fmtBytes[25].toInt() and 0xFF) shl 8)
                }

                val isPcm = audioFormat == 1 || (audioFormat == 0xFFFE && subFormat == 1)
                if (!isPcm) {
                    throw UnsupportedOperationException("Unsupported WAV format: audioFormat=$audioFormat, subFormat=$subFormat; only PCM is supported")
                }
                if (bitsPerSample != 16) {
                    throw UnsupportedOperationException("Unsupported bit depth: $bitsPerSample bits; only 16-bit PCM is supported")
                }
                if (channels <= 0) {
                    throw IOException("Malformed WAV: invalid channel count $channels")
                }
                if (sampleRate <= 0) {
                    throw IOException("Malformed WAV: invalid sample rate $sampleRate")
                }
                fmtFound = true
            }
            "data" -> {
                dataOffset = chunkDataOffset
                dataSize = chunkSize
            }
        }
        offset = chunkDataOffset + chunkSize + (chunkSize and 1L)
    }

    if (!fmtFound) throw IOException("Malformed WAV: missing fmt chunk")
    if (dataOffset < 0) throw IOException("Malformed WAV: missing data chunk")

    WavInfo(
        audioFormat = audioFormat,
        channels = channels,
        sampleRateHz = sampleRate,
        bitsPerSample = bitsPerSample,
        dataOffset = dataOffset,
        dataSize = dataSize,
    )
}

private fun streamWavPcm(
    source: File,
    wavInfo: WavInfo,
    writer: MonoPcmWavWriter,
    checkCancelled: () -> Unit,
) {
    RandomAccessFile(source, "r").use { raf ->
        raf.seek(wavInfo.dataOffset)
        val buf = ByteArray(WAV_SLICE_BYTES)
        var left = wavInfo.dataSize
        while (left > 0) {
            checkCancelled()
            val toRead = minOf(buf.size.toLong(), left).toInt()
            val n = raf.read(buf, 0, toRead)
            if (n < 0) {
                throw IOException("Premature end of file reading WAV data: expected $left more bytes")
            }
            writer.write(buf, n)
            left -= n
        }
    }
}

private fun streamCopy(source: File, dest: File, checkCancelled: () -> Unit) {
    FileInputStream(source).use { input ->
        FileOutputStream(dest).use { output ->
            val buf = ByteArray(WAV_SLICE_BYTES)
            while (true) {
                checkCancelled()
                val n = input.read(buf)
                if (n <= 0) break
                output.write(buf, 0, n)
            }
            output.fd.sync()
        }
    }
}
