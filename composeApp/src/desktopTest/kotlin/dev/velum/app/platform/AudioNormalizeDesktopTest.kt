package dev.velum.app.platform

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class AudioNormalizeDesktopTest {

    @Test
    fun cancellation_interrupts_chunk_scan_and_hash_after_progress() = withTempRoots { input, _ ->
        val source = writePcmWav(File(input, "chunks.wav"), pcm = ByteArray(2),
            extraChunksBefore = List(8) { "JUNK" to ByteArray(8) })
        val cancellation = CancellationException("cancel during scan")
        var chunks = 0
        val scanFailure = assertFailsWith<CancellationException> {
            readWavInfo(source) { if (++chunks == 3) throw cancellation }
        }
        assertTrue(scanFailure === cancellation)
        assertEquals(3, chunks)

        val hashSource = File(input, "hash.wav").apply { writeBytes(ByteArray(128 * 1024 + 17) { it.toByte() }) }
        val expectedHash = sha256File(hashSource.absolutePath)
        var reads = 0
        val hashFailure = assertFailsWith<CancellationException> {
            sha256FileWithCancellation(hashSource.absolutePath) { if (++reads == 2) throw cancellation }
        }
        assertTrue(hashFailure === cancellation)
        assertEquals(2, reads)
        assertEquals(expectedHash, sha256File(hashSource.absolutePath))
    }

    @Test
    fun extensible_pcm_requires_full_guid_and_consistent_extension_fields() = withTempRoots { input, _ ->
        val source = writePcmWav(File(input, "extensible.wav"), pcm = ByteArray(8))
        val pcm = source.readBytes()
        val valid = ByteBuffer.allocate(pcm.size + 24).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(pcm, 0, 36)
            putShort(22)
            putShort(16)
            putInt(4) // mono front-center
            put(byteArrayOf(1, 0, 0, 0, 0, 0, 16, 0, -128, 0, 0, -86, 0, 56, -101, 113))
            put(pcm, 36, pcm.size - 36)
            putInt(4, capacity() - 8)
            putInt(16, 40)
            putShort(20, 0xFFFE.toShort())
        }.array()
        source.writeBytes(valid)
        assertEquals(8L, readWavInfo(source).dataSize)
        assertEquals(16, readWavInfo(source).bitsPerSample)
        // Every subtype byte matters, including bytes beyond the original two-byte check.
        for (offset in 44 until 60) {
            val invalid = valid.copyOf()
            invalid[offset] = (invalid[offset].toInt() xor 1).toByte()
            source.writeBytes(invalid)
            assertFailsWith<UnsupportedOperationException> { readWavInfo(source) }
        }
        for ((offset, value) in listOf(36 to 21, 38 to 12, 40 to 3, 32 to 4, 28 to 1)) {
            val invalid = valid.copyOf()
            invalid[offset] = value.toByte()
            source.writeBytes(invalid)
            assertFailsWith<IOException> { readWavInfo(source) }
        }
    }

    private inline fun withTempRoots(block: (inputDir: File, outputDir: File) -> Unit) {
        val root = Files.createTempDirectory("velum-desktop-normalize-test").toFile()
        try {
            val inputDir = File(root, "input").apply { mkdirs() }
            val outputDir = File(root, "output").apply { mkdirs() }
            block(inputDir, outputDir)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun writePcmWav(
        file: File,
        sampleRate: Int = 16_000,
        channels: Int = 1,
        bitsPerSample: Int = 16,
        audioFormat: Int = 1,
        pcm: ByteArray,
        extraChunksBefore: List<Pair<String, ByteArray>> = emptyList(),
        extraChunksAfter: List<Pair<String, ByteArray>> = emptyList(),
    ): File {
        file.parentFile?.mkdirs()
        RandomAccessFile(file, "rw").use { raf ->
            val byteRate = sampleRate * channels * bitsPerSample / 8
            val blockAlign = channels * bitsPerSample / 8
            var totalData = 4L // "WAVE"

            // fmt chunk
            totalData += 8 + 16

            // extra chunks before
            for ((id, data) in extraChunksBefore) {
                totalData += 8 + data.size + (data.size and 1)
            }

            // data chunk
            totalData += 8 + pcm.size + (pcm.size and 1)

            // extra chunks after
            for ((id, data) in extraChunksAfter) {
                totalData += 8 + data.size + (data.size and 1)
            }

            // RIFF header
            val header = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray())
            header.putInt(totalData.toInt())
            header.put("WAVE".toByteArray())
            raf.write(header.array())

            // fmt chunk
            val fmt = ByteBuffer.allocate(8 + 16).order(ByteOrder.LITTLE_ENDIAN)
            fmt.put("fmt ".toByteArray())
            fmt.putInt(16)
            fmt.putShort(audioFormat.toShort())
            fmt.putShort(channels.toShort())
            fmt.putInt(sampleRate)
            fmt.putInt(byteRate)
            fmt.putShort(blockAlign.toShort())
            fmt.putShort(bitsPerSample.toShort())
            raf.write(fmt.array())

            // extra chunks before
            for ((id, data) in extraChunksBefore) {
                val ch = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                ch.put(id.padEnd(4).take(4).toByteArray())
                ch.putInt(data.size)
                raf.write(ch.array())
                raf.write(data)
                if (data.size % 2 == 1) raf.write(0)
            }

            // data chunk
            val dataHdr = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            dataHdr.put("data".toByteArray())
            dataHdr.putInt(pcm.size)
            raf.write(dataHdr.array())
            raf.write(pcm)
            if (pcm.size % 2 == 1) raf.write(0)

            // extra chunks after
            for ((id, data) in extraChunksAfter) {
                val ch = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                ch.put(id.padEnd(4).take(4).toByteArray())
                ch.putInt(data.size)
                raf.write(ch.array())
                raf.write(data)
                if (data.size % 2 == 1) raf.write(0)
            }
        }
        return file
    }

    @Test
    fun wav_normalizes_to_pcm16_mono_and_matches_output_hash_and_duration() = runBlocking {
        withTempRoots { inputDir, outputDir ->
            val samples = 16_000 // 1 second of 16kHz audio
            val pcm = ByteBuffer.allocate(samples * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
                for (i in 0 until samples) putShort((i % 1000).toShort())
            }.array()
            val src = writePcmWav(File(inputDir, "source.wav"), sampleRate = 16_000, channels = 1, pcm = pcm)

            val normalizer = AudioNormalizer(outputDir)
            val result = normalizer.normalizeToWav(src.absolutePath, targetSampleRateHz = 16_000)

            assertEquals(16_000, result.sampleRateHz)
            assertEquals(1000L, result.durationMs)
            assertEquals("audio/wav", result.sourceMime)
            val outFile = File(result.wavPath)
            assertTrue(outFile.isFile)
            assertEquals(outputDir.canonicalPath, outFile.parentFile.canonicalPath)
            assertEquals(sha256File(outFile.absolutePath), result.contentSha256)

            // Verify WAV header of normalized output
            val header = ByteArray(44).also { RandomAccessFile(outFile, "r").use { raf -> raf.readFully(it) } }
            val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals("RIFF", header.decodeToString(0, 4))
            assertEquals(36 + samples * 2, bb.getInt(4))
            assertEquals("WAVE", header.decodeToString(8, 12))
            assertEquals("fmt ", header.decodeToString(12, 16))
            assertEquals(1, bb.getShort(20).toInt()) // PCM
            assertEquals(1, bb.getShort(22).toInt()) // mono
            assertEquals(16_000, bb.getInt(24)) // sample rate
            assertEquals(16, bb.getShort(34).toInt()) // 16-bit
            assertEquals("data", header.decodeToString(36, 40))
            assertEquals(samples * 2, bb.getInt(40))
        }
    }

    @Test
    fun stereo_downmixes_channels_and_resamples_rate_accurately() = runBlocking {
        withTempRoots { inputDir, outputDir ->
            // Stereo 44.1 kHz, 44,100 frames = 1 second.
            // Left: constant 1000, Right: constant 3000 -> downmixed mono should be 2000.
            val frames = 44_100
            val pcm = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
                repeat(frames) {
                    putShort(1000.toShort())
                    putShort(3000.toShort())
                }
            }.array()
            val src = writePcmWav(File(inputDir, "stereo.wav"), sampleRate = 44_100, channels = 2, pcm = pcm)

            val normalizer = AudioNormalizer(outputDir)
            val result = normalizer.normalizeToWav(src.absolutePath, targetSampleRateHz = 16_000)

            assertEquals(16_000, result.sampleRateHz)
            // 44,100 frames at 44.1kHz is exactly 1000ms
            assertEquals(1000L, result.durationMs)
            val outFile = File(result.wavPath)
            assertTrue(outFile.isFile)
            assertEquals(sha256File(outFile.absolutePath), result.contentSha256)

            // Check downmixed samples: at 16kHz, length is 16,000 samples * 2 = 32,000 bytes
            val outData = outFile.readBytes().copyOfRange(44, outFile.length().toInt())
            val outBb = ByteBuffer.wrap(outData).order(ByteOrder.LITTLE_ENDIAN)
            val sampleCount = outData.size / 2
            assertEquals(16_000, sampleCount)
            for (i in 0 until sampleCount) {
                val s = outBb.short
                // Constant downmix of (1000 + 3000)/2 = 2000
                assertEquals(2000.toShort(), s, "sample $i mismatch")
            }
        }
    }

    @Test
    fun extra_chunks_and_odd_chunks_parse_without_corruption() = runBlocking {
        withTempRoots { inputDir, outputDir ->
            // 15-byte odd JUNK chunk before data, 7-byte odd LIST chunk after data
            val pcm = ByteBuffer.allocate(320).order(ByteOrder.LITTLE_ENDIAN).apply {
                repeat(160) { putShort((it * 10).toShort()) }
            }.array()
            val junkOdd = ByteArray(15) { (it + 1).toByte() }
            val listOdd = ByteArray(7) { (it + 2).toByte() }

            val src = writePcmWav(
                File(inputDir, "odd_chunks.wav"),
                sampleRate = 16_000,
                channels = 1,
                pcm = pcm,
                extraChunksBefore = listOf("JUNK" to junkOdd),
                extraChunksAfter = listOf("LIST" to listOdd),
            )

            val normalizer = AudioNormalizer(outputDir)
            val result = normalizer.normalizeToWav(src.absolutePath, targetSampleRateHz = 16_000)

            assertEquals(16_000, result.sampleRateHz)
            assertEquals(10L, result.durationMs) // 160 samples at 16kHz = 10ms
            val outFile = File(result.wavPath)
            val outPcm = outFile.readBytes().copyOfRange(44, outFile.length().toInt())
            assertContentEquals(pcm, outPcm)
        }
    }

    @Test
    fun odd_data_chunk_tail_drops_dangling_byte() = runBlocking {
        withTempRoots { inputDir, outputDir ->
            // 700,001 data bytes: odd last byte is not a whole 16-bit sample
            val pcm = ByteArray(700_001) { (it * 7).toByte() }
            val src = writePcmWav(File(inputDir, "odd_tail.wav"), sampleRate = 16_000, channels = 1, pcm = pcm)

            val normalizer = AudioNormalizer(outputDir)
            val result = normalizer.normalizeToWav(src.absolutePath, targetSampleRateHz = 16_000)

            val outFile = File(result.wavPath)
            val whole = pcm.size - 1
            assertEquals(44 + whole, outFile.length().toInt())
            val outPcm = outFile.readBytes().copyOfRange(44, outFile.length().toInt())
            assertContentEquals(pcm.copyOf(whole), outPcm)
        }
    }

    @Test
    fun malformed_and_unsupported_inputs_fail_without_relabeling_garbage() = runBlocking {
        withTempRoots { inputDir, outputDir ->
            val normalizer = AudioNormalizer(outputDir)

            // 1. Not a RIFF file
            val notRiff = File(inputDir, "not_riff.wav").apply { writeText("this is plain text not riff") }
            assertFailsWith<IOException> { normalizer.normalizeToWav(notRiff.absolutePath, 16_000) }

            // 2. RIFF but not WAVE
            val notWave = File(inputDir, "not_wave.wav")
            RandomAccessFile(notWave, "rw").use { raf ->
                val b = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
                b.put("RIFF".toByteArray()).putInt(16).put("AVI ".toByteArray())
                raf.write(b.array())
            }
            assertFailsWith<IOException> { normalizer.normalizeToWav(notWave.absolutePath, 16_000) }

            // 3. Cut-off fmt chunk (fmt claims 16 bytes, only 6 follow)
            val cutFmt = File(inputDir, "cut_fmt.wav")
            val cutBytes = ByteBuffer.allocate(12 + 8 + 6).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray()).putInt(26).put("WAVE".toByteArray()).put("fmt ".toByteArray()).putInt(16)
                putShort(1).putShort(1).putShort(0)
            }.array()
            cutFmt.writeBytes(cutBytes)
            assertFailsWith<IOException> { normalizer.normalizeToWav(cutFmt.absolutePath, 16_000) }

            // 4. Missing data chunk
            val noData = File(inputDir, "no_data.wav")
            val noDataBytes = ByteBuffer.allocate(12 + 8 + 16).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray()).putInt(28).put("WAVE".toByteArray()).put("fmt ".toByteArray()).putInt(16)
                putShort(1).putShort(1).putInt(16_000).putInt(32_000).putShort(2).putShort(16)
            }.array()
            noData.writeBytes(noDataBytes)
            assertFailsWith<IOException> { normalizer.normalizeToWav(noData.absolutePath, 16_000) }

            // 5. Unsupported format: IEEE float (audioFormat = 3) must be rejected, not relabeled!
            val floatWav = writePcmWav(
                File(inputDir, "float.wav"),
                sampleRate = 16_000,
                channels = 1,
                bitsPerSample = 32,
                audioFormat = 3, // IEEE float
                pcm = ByteArray(64),
            )
            assertFailsWith<UnsupportedOperationException> { normalizer.normalizeToWav(floatWav.absolutePath, 16_000) }

            // 6. Unsupported bit depth: 8-bit PCM must be rejected, not relabeled!
            val eightBitWav = writePcmWav(
                File(inputDir, "eight_bit.wav"),
                sampleRate = 16_000,
                channels = 1,
                bitsPerSample = 8,
                audioFormat = 1,
                pcm = ByteArray(64),
            )
            assertFailsWith<UnsupportedOperationException> { normalizer.normalizeToWav(eightBitWav.absolutePath, 16_000) }

            // Ensure no partial or generated files remain in outputDir after failures
            assertTrue(outputDir.listFiles().orEmpty().isEmpty())
        }
    }

    @Test
    fun cancellation_during_normalize_deletes_partial_file_and_leaves_no_output() = runBlocking {
        withTempRoots { inputDir, outputDir ->
            val big = File(inputDir, "huge.wav")
            val dataBytes = 64L * 1024 * 1024 // 64 MB sparse audio
            RandomAccessFile(big, "rw").use { raf ->
                val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                h.put("RIFF".toByteArray()).putInt((36 + dataBytes).toInt()).put("WAVE".toByteArray()).put("fmt ".toByteArray()).putInt(16)
                h.putShort(1).putShort(1).putInt(16_000).putInt(32_000).putShort(2).putShort(16)
                h.put("data".toByteArray()).putInt(dataBytes.toInt())
                raf.write(h.array())
                raf.setLength(44 + dataBytes)
            }

            val normalizer = AudioNormalizer(outputDir)
            val job = async(Dispatchers.Default) {
                normalizer.normalizeToWav(big.absolutePath, 16_000)
            }

            // Wait until writing starts
            withTimeout(10_000) {
                while (outputDir.listFiles().orEmpty().none { it.length() > 0L }) {
                    delay(1)
                }
            }

            job.cancel()
            try {
                job.await()
                kotlin.test.fail("Normalize should have been cancelled")
            } catch (_: CancellationException) {
            }

            // Verify no .part or .wav file remains
            val remaining = outputDir.listFiles().orEmpty()
            assertTrue(remaining.isEmpty(), "Cancelled normalize left files: ${remaining.map { it.name }}")
        }
    }

    @Test
    fun cleanup_only_cleans_own_outputs_and_preserves_earlier_outputs() = runBlocking {
        withTempRoots { inputDir, outputDir ->
            val existing = File(outputDir, "import_earlier_user_recording.wav").apply {
                writeText("pre-existing user recording")
            }
            val bad = File(inputDir, "bad.wav").apply { writeText("garbage") }

            val normalizer = AudioNormalizer(outputDir)
            assertFailsWith<IOException> { normalizer.normalizeToWav(bad.absolutePath, 16_000) }

            // Pre-existing recording was not touched or deleted
            assertTrue(existing.isFile)
            assertEquals("pre-existing user recording", existing.readText())
            // No .part files left
            assertTrue(outputDir.listFiles().orEmpty().none { it.name.endsWith(".part") })
        }
    }

    @Test
    fun two_imports_of_the_same_source_produce_independent_uuid_outputs() = runBlocking {
        withTempRoots { inputDir, outputDir ->
            val pcm = ByteArray(3200) { (it % 256).toByte() }
            val src = writePcmWav(File(inputDir, "same.wav"), sampleRate = 16_000, channels = 1, pcm = pcm)

            val normalizer = AudioNormalizer(outputDir)
            val first = normalizer.normalizeToWav(src.absolutePath, 16_000)
            val second = normalizer.normalizeToWav(src.absolutePath, 16_000)

            assertNotEquals(first.wavPath, second.wavPath)
            assertTrue(File(first.wavPath).isFile)
            assertTrue(File(second.wavPath).isFile)
            assertEquals(first.contentSha256, second.contentSha256)
        }
    }

    @Test
    fun ogg_opus_passthrough_preserves_source_and_calculates_output_hash() = runBlocking {
        withTempRoots { inputDir, outputDir ->
            val oggBytes = ByteArray(1024) { (it * 3).toByte() }
            val ogg = File(inputDir, "sample.ogg").apply { writeBytes(oggBytes) }

            val normalizer = AudioNormalizer(outputDir)
            val result = normalizer.normalizeToWav(ogg.absolutePath, targetSampleRateHz = 16_000)

            assertEquals(16_000, result.sampleRateHz)
            assertEquals(0L, result.durationMs)
            assertEquals("audio/ogg", result.sourceMime)
            val outFile = File(result.wavPath)
            assertTrue(outFile.isFile)
            assertTrue(outFile.name.endsWith(".ogg"))
            assertContentEquals(oggBytes, outFile.readBytes())
            assertEquals(sha256File(outFile.absolutePath), result.contentSha256)
        }
    }

    @Test
    fun unsupported_compressed_formats_throw_informative_error() = runBlocking {
        withTempRoots { inputDir, outputDir ->
            val normalizer = AudioNormalizer(outputDir)
            for (ext in listOf("mp3", "m4a", "flac", "amr", "webm")) {
                val file = File(inputDir, "track.$ext").apply { writeBytes(ByteArray(100)) }
                val error = assertFailsWith<IllegalStateException> {
                    normalizer.normalizeToWav(file.absolutePath, 16_000)
                }
                assertTrue(error.message!!.contains("Desktop build cannot decode $ext"))
            }
        }
    }

    @Test
    fun large_input_memory_is_bounded_and_does_not_call_readBytes() = runBlocking {
        withTempRoots { inputDir, outputDir ->
            // Source-level guard: AudioNormalize.desktop.kt must never use readBytes()
            val desktopSource = listOf(
                File("src/desktopMain/kotlin/dev/velum/app/platform/AudioNormalize.desktop.kt"),
                File("composeApp/src/desktopMain/kotlin/dev/velum/app/platform/AudioNormalize.desktop.kt"),
            ).first { it.isFile }.readText()
            assertFalse(desktopSource.contains("readBytes()"), "AudioNormalize.desktop.kt must not call readBytes()")
            assertFalse(desktopSource.contains("ByteBuffer.allocate(44 + dataSize)"), "Must not allocate full-length buffer")

            // Meaningful large input test: 64 MB audio (32 million samples, ~33 minutes of mono 16kHz)
            val big = File(inputDir, "large_sparse.wav")
            val dataBytes = 64L * 1024 * 1024
            RandomAccessFile(big, "rw").use { raf ->
                val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                h.put("RIFF".toByteArray()).putInt((36 + dataBytes).toInt()).put("WAVE".toByteArray()).put("fmt ".toByteArray()).putInt(16)
                h.putShort(1).putShort(1).putInt(16_000).putInt(32_000).putShort(2).putShort(16)
                h.put("data".toByteArray()).putInt(dataBytes.toInt())
                raf.write(h.array())
                raf.setLength(44 + dataBytes)
            }

            val normalizer = AudioNormalizer(outputDir)
            val result = normalizer.normalizeToWav(big.absolutePath, targetSampleRateHz = 16_000)

            val outFile = File(result.wavPath)
            assertTrue(outFile.isFile)
            assertEquals(44 + dataBytes, outFile.length())
            val expectedDurationMs = (dataBytes / 2.0 / 16_000 * 1000.0).toLong()
            assertEquals(expectedDurationMs, result.durationMs)
            assertEquals(sha256File(outFile.absolutePath), result.contentSha256)
        }
    }
}
