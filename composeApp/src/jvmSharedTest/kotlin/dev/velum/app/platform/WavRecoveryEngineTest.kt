package dev.velum.app.platform

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException

/** Runs unchanged against the Android and desktop JVM recovery implementation. */
class WavRecoveryEngineTest {
    @Test fun partial_publication_preserves_source_and_is_idempotent_without_aliasing_source() = fixture { dir ->
        val source = wave(dir, "rec-one.wav.partial", stale = true); val before = source.readBytes()
        val first = assertIs<WavRecoveryResult.Recovered>(WavWriter.recoverWithReport(source.path))
        val target = File(first.finish.absolutePath); val published = target.readBytes()
        assertTrue(first.partialPreserved); assertFalse(first.reusedFinal)
        assertContentEquals(before, source.readBytes()); assertFalse(Files.isSameFile(source.toPath(), target.toPath()))
        val second = assertIs<WavRecoveryResult.Recovered>(WavWriter.recoverWithReport(source.path))
        assertTrue(second.reusedFinal); assertContentEquals(published, target.readBytes())
        source.appendBytes(byteArrayOf(1, 2))
        assertIs<WavRecoveryResult.Conflict>(WavWriter.recoverWithReport(source.path))
        assertContentEquals(published, target.readBytes())
    }
    @Test fun invalid_or_empty_partial_keeps_existing_final_and_source_byte_for_byte() = fixture { dir ->
        val target = wave(dir, "rec-one.wav"); val before = target.readBytes()
        for (bytes in listOf(byteArrayOf(), byteArrayOf(1, 2, 3), ByteArray(44), ByteArray(80) { 3 })) {
            val source = File(dir, "rec-one.wav.partial").apply { writeBytes(bytes) }
            assertIs<WavRecoveryResult.Invalid>(WavWriter.recoverWithReport(source.path))
            assertContentEquals(before, target.readBytes()); assertContentEquals(bytes, source.readBytes())
        }
    }
    @Test fun different_valid_or_invalid_final_is_a_conflict_and_never_replaced() = fixture { dir ->
        val source = wave(dir, "rec-one.wav.partial", sample = 1); val original = source.readBytes()
        val target = wave(dir, "rec-one.wav", sample = 2)
        for (bytes in listOf(target.readBytes(), byteArrayOf(1, 2, 3))) {
            target.writeBytes(bytes)
            assertIs<WavRecoveryResult.Conflict>(WavWriter.recoverWithReport(source.path))
            assertContentEquals(original, source.readBytes()); assertContentEquals(bytes, target.readBytes())
        }
    }
    @Test fun mixed_case_partial_suffix_and_legacy_completed_rename_are_supported() = fixture { dir ->
        val source = wave(dir, "rec-one.WaV.PaRtIaL", stale = true)
        val recovered = assertIs<WavRecoveryResult.Recovered>(WavWriter.recoverWithReport(source.path))
        assertEquals(File(dir, "rec-one.WaV").path, recovered.finish.absolutePath)
        source.delete() // Synthetic legacy rename state; production recovery never removes partials.
        val retry = assertIs<WavRecoveryResult.Recovered>(WavWriter.recoverWithReport(source.path))
        assertTrue(retry.reusedFinal)
        assertTrue(File(retry.finish.absolutePath).exists())
    }
    @Test fun missing_paths_and_non_regular_files_are_preserved_without_publication() = fixture { dir ->
        assertIs<WavRecoveryResult.Missing>(WavWriter.recoverWithReport(File(dir, "absent.wav").path))
        val source = File(dir, "directory.wav.partial").apply { mkdir() }
        File(source, "keep").writeText("synthetic")
        assertIs<WavRecoveryResult.Invalid>(WavWriter.recoverWithReport(source.path))
        assertEquals("synthetic", File(source, "keep").readText()); assertFalse(File(dir, "directory.wav").exists())
    }
    @Test fun unsupported_headers_and_incomplete_pcm_frames_are_rejected_without_any_rewrite() = fixture { dir ->
        val valid = wave(dir, "template.wav").readBytes()
        val mutations: List<(ByteArray) -> ByteArray> = listOf(
            { it.apply { this[0] = 0 } }, { it.apply { this[8] = 0 } }, { it.apply { this[12] = 0 } },
            { it.apply { this[36] = 0 } }, { it.apply { ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).putInt(16, 18) } },
            { it.apply { this[20] = 3 } }, { it.apply { this[22] = 2 } },
            { it.apply { ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).putInt(24, 8_000) } },
            { it.apply { ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).putInt(28, 32_001) } }, { it.apply { this[32] = 1 } }, { it.apply { this[34] = 8 } },
            { it + byteArrayOf(1) },
        )
        mutations.forEachIndexed { i, mutation ->
            val bytes = mutation(valid.copyOf()); val file = File(dir, "bad-$i.wav.partial").apply { writeBytes(bytes) }
            assertIs<WavRecoveryResult.Invalid>(WavWriter.recoverWithReport(file.path))
            assertContentEquals(bytes, file.readBytes()); assertFalse(File(dir, "bad-$i.wav").exists())
        }
    }
    @Test fun configured_sample_rate_and_shorter_even_pcm_repair_are_format_correct() = fixture { dir ->
        val file = wave(dir, "rec-rate.wav", rate = 44_100, stale = true)
        assertIs<WavRecoveryResult.Invalid>(WavWriter.recoverWithReport(file.path, 16_000))
        val before = file.readBytes()
        val result = assertIs<WavRecoveryResult.Recovered>(WavWriter.recoverWithReport(file.path, 44_100))
        val after = file.readBytes()
        assertEquals(3200L, unsignedInt(after, 40)); assertEquals(3236L, unsignedInt(after, 4))
        assertContentEquals(before.copyOfRange(44, before.size), after.copyOfRange(44, after.size))
        assertEquals(file.path, result.finish.absolutePath)
    }
    @Test fun read_failure_leaves_source_and_target_untouched() = fixture { dir ->
        val source = wave(dir, "rec-one.wav.partial"); val before = source.readBytes()
        val io = object : JvmWavRecoveryIo() { override fun reader(path: Path): RecoveryReader = throw IOException("synthetic read failure") }
        val result = assertIs<WavRecoveryResult.Unavailable>(recoverJvmWav(source.path, 16_000, io))
        assertEquals(WavRecoveryOperation.Read, result.operation); assertContentEquals(before, source.readBytes())
        assertFalse(File(dir, "rec-one.wav").exists())
    }
    @Test fun copy_failure_removes_only_owned_scratch_and_keeps_original() = fixture { dir ->
        val source = wave(dir, "rec-one.wav.partial"); val before = source.readBytes()
        val io = object : JvmWavRecoveryIo() {
            override fun stage(parent: Path): RecoveryStage {
                val actual = super.stage(parent)
                return object : RecoveryStage by actual { override fun write(buffer: ByteArray, offset: Int, length: Int) { throw IOException("synthetic write failure") } }
            }
        }
        assertIs<WavRecoveryResult.Unavailable>(recoverJvmWav(source.path, 16_000, io))
        assertContentEquals(before, source.readBytes()); assertEquals(setOf(source.name), dir.list()!!.toSet())
    }
    @Test fun sync_failure_never_publishes_unacknowledged_data() = fixture { dir ->
        val source = wave(dir, "rec-one.wav.partial"); val before = source.readBytes()
        val io = object : JvmWavRecoveryIo() {
            override fun stage(parent: Path): RecoveryStage {
                val actual = super.stage(parent)
                return object : RecoveryStage by actual { override fun sync() { throw IOException("synthetic sync failure") } }
            }
        }
        assertIs<WavRecoveryResult.Unavailable>(recoverJvmWav(source.path, 16_000, io))
        assertContentEquals(before, source.readBytes()); assertEquals(setOf(source.name), dir.list()!!.toSet())
    }
    @Test fun copy_verification_rejects_corrupted_scratch_without_changing_source() = fixture { dir ->
        val source = wave(dir, "rec-one.wav.partial"); val before = source.readBytes()
        val io = object : JvmWavRecoveryIo() {
            override fun stage(parent: Path): RecoveryStage {
                val actual = super.stage(parent); var writes = 0
                return object : RecoveryStage by actual {
                    override fun write(buffer: ByteArray, offset: Int, length: Int) {
                        if (writes++ > 0) { val bad = buffer.copyOf(); bad[offset] = (bad[offset].toInt() xor 1).toByte(); actual.write(bad, offset, length) }
                        else actual.write(buffer, offset, length)
                    }
                }
            }
        }
        val result = assertIs<WavRecoveryResult.Unavailable>(recoverJvmWav(source.path, 16_000, io))
        assertEquals(WavRecoveryOperation.VerifyCopy, result.operation); assertContentEquals(before, source.readBytes())
        assertFalse(File(dir, "rec-one.wav").exists())
    }
    @Test fun changed_source_during_copy_is_kept_and_not_published() = fixture { dir ->
        val source = wave(dir, "rec-one.wav.partial"); var changed = byteArrayOf()
        val io = object : JvmWavRecoveryIo() {
            override fun stage(parent: Path): RecoveryStage {
                val actual = super.stage(parent)
                return object : RecoveryStage by actual { override fun sync() { actual.sync(); source.appendBytes(byteArrayOf(3, 4)); changed = source.readBytes() } }
            }
        }
        assertIs<WavRecoveryResult.Changed>(recoverJvmWav(source.path, 16_000, io))
        assertContentEquals(changed, source.readBytes()); assertFalse(File(dir, "rec-one.wav").exists())
    }
    @Test fun a_racing_target_wins_without_being_overwritten_or_deleted() = fixture { dir ->
        val source = wave(dir, "rec-one.wav.partial", sample = 1); val before = source.readBytes(); var winner = byteArrayOf()
        val io = object : JvmWavRecoveryIo() {
            override fun publish(stage: Path, target: Path) { wave(dir, "rec-one.wav", sample = 8); winner = target.toFile().readBytes(); super.publish(stage, target) }
        }
        assertIs<WavRecoveryResult.Conflict>(recoverJvmWav(source.path, 16_000, io))
        assertContentEquals(before, source.readBytes()); assertContentEquals(winner, File(dir, "rec-one.wav").readBytes())
    }
    @Test fun unsupported_exclusive_publication_has_no_overwrite_fallback() = fixture { dir ->
        val source = wave(dir, "rec-one.wav.partial"); val before = source.readBytes()
        val io = object : JvmWavRecoveryIo() { override fun publish(stage: Path, target: Path) { throw UnsupportedOperationException("synthetic unsupported exclusive creation") } }
        val result = assertIs<WavRecoveryResult.Unavailable>(recoverJvmWav(source.path, 16_000, io))
        assertEquals(WavRecoveryOperation.Publish, result.operation); assertContentEquals(before, source.readBytes())
        assertFalse(File(dir, "rec-one.wav").exists())
    }
    @Test fun interrupted_publication_after_creation_is_safe_and_retry_reuses_identical_final() = fixture { dir ->
        val source = wave(dir, "rec-one.wav.partial", stale = true); val before = source.readBytes()
        val io = object : JvmWavRecoveryIo() { override fun publish(stage: Path, target: Path) { super.publish(stage, target); throw IOException("synthetic lost completion") } }
        assertIs<WavRecoveryResult.Conflict>(recoverJvmWav(source.path, 16_000, io))
        val target = File(dir, "rec-one.wav"); val published = target.readBytes()
        val retry = assertIs<WavRecoveryResult.Recovered>(WavWriter.recoverWithReport(source.path))
        assertTrue(retry.reusedFinal); assertContentEquals(before, source.readBytes()); assertContentEquals(published, target.readBytes())
    }
    @Test fun an_incomplete_exclusively_created_destination_is_preserved_as_a_conflict_on_retry() = fixture { dir ->
        val source = wave(dir, "rec-one.wav.partial"); val before = source.readBytes()
        val io = object : JvmWavRecoveryIo() {
            override fun destination(target: Path): RecoveryDestination {
                val actual = super.destination(target); var writes = 0
                return object : RecoveryDestination by actual {
                    override fun write(buffer: ByteArray, offset: Int, length: Int) {
                        if (writes++ == 0) actual.write(buffer, offset, length)
                        else { actual.write(buffer, offset, 2); throw IOException("synthetic publication interruption") }
                    }
                }
            }
        }
        assertIs<WavRecoveryResult.Conflict>(recoverJvmWav(source.path, 16_000, io))
        val target = File(dir, "rec-one.wav"); val incomplete = target.readBytes()
        assertTrue(incomplete.take(44).all { it == 0.toByte() })
        assertContentEquals(before, source.readBytes())
        assertIs<WavRecoveryResult.Conflict>(WavWriter.recoverWithReport(source.path))
        assertContentEquals(incomplete, target.readBytes()); assertContentEquals(before, source.readBytes())
    }
    @Test fun an_existing_matching_final_with_stale_sizes_is_repaired_only_after_audio_equality() = fixture { dir ->
        val source = wave(dir, "rec-one.wav.partial", sample = 3); val before = source.readBytes()
        val target = wave(dir, "rec-one.wav", sample = 3, stale = true); val targetBefore = target.readBytes()
        val result = assertIs<WavRecoveryResult.Recovered>(WavWriter.recoverWithReport(source.path))
        assertTrue(result.reusedFinal); assertContentEquals(before, source.readBytes())
        assertContentEquals(targetBefore.copyOfRange(44, targetBefore.size), target.readBytes().copyOfRange(44, targetBefore.size))
        assertEquals(3200L, unsignedInt(target.readBytes(), 40))
    }
    @Test fun a_target_replaced_after_publication_is_not_selected_as_the_recovered_audio() = fixture { dir ->
        val source = wave(dir, "rec-one.wav.partial", sample = 1); val before = source.readBytes(); var winner = byteArrayOf()
        val io = object : JvmWavRecoveryIo() {
            override fun publish(stage: Path, target: Path) { super.publish(stage, target); wave(dir, "rec-one.wav", sample = 8); winner = target.toFile().readBytes() }
        }
        assertIs<WavRecoveryResult.Conflict>(recoverJvmWav(source.path, 16_000, io))
        assertContentEquals(winner, File(dir, "rec-one.wav").readBytes()); assertContentEquals(before, source.readBytes())
    }
    @Test fun interrupted_size_field_repair_is_retryable_and_never_changes_pcm_or_format() = fixture { dir ->
        val file = wave(dir, "rec-one.wav", stale = true); val before = file.readBytes()
        val io = object : JvmWavRecoveryIo() {
            override fun repairSizes(path: Path, expected: RecoveryStamp, original: ByteArray, canonical: ByteArray) {
                RandomAccessFile(path.toFile(), "rw").use { it.seek(4); it.write(canonical, 4, 4) }
                throw IOException("synthetic interrupted header repair")
            }
        }
        val failed = assertIs<WavRecoveryResult.Unavailable>(recoverJvmWav(file.path, 16_000, io))
        assertEquals(WavRecoveryOperation.RepairHeader, failed.operation)
        assertContentEquals(before.copyOfRange(44, before.size), file.readBytes().copyOfRange(44, before.size))
        assertIs<WavRecoveryResult.Recovered>(WavWriter.recoverWithReport(file.path))
        val after = file.readBytes(); assertEquals(3200L, unsignedInt(after, 40))
        for (i in before.indices) if (i !in 4..7 && i !in 40..43) assertEquals(before[i], after[i])
    }
    @Test fun cancellation_propagates_and_owned_scratch_is_cleaned_without_source_deletion() = fixture { dir ->
        val source = wave(dir, "rec-one.wav.partial"); val before = source.readBytes()
        val io = object : JvmWavRecoveryIo() {
            override fun stage(parent: Path): RecoveryStage {
                val actual = super.stage(parent)
                return object : RecoveryStage by actual { override fun sync() { throw CancellationException("synthetic cancellation") } }
            }
        }
        try { recoverJvmWav(source.path, 16_000, io); error("Expected cancellation") } catch (_: CancellationException) { }
        assertContentEquals(before, source.readBytes()); assertEquals(setOf(source.name), dir.list()!!.toSet())
    }
    @Test fun symlink_source_and_destination_are_not_followed_or_replaced() = fixture { dir ->
        val real = wave(dir, "real.wav"); val before = real.readBytes()
        val sourceLink = File(dir, "linked.wav.partial"); Files.createSymbolicLink(sourceLink.toPath(), real.toPath())
        assertIs<WavRecoveryResult.Invalid>(WavWriter.recoverWithReport(sourceLink.path))
        val source = wave(dir, "rec-one.wav.partial"); val targetLink = File(dir, "rec-one.wav")
        Files.createSymbolicLink(targetLink.toPath(), real.toPath())
        assertIs<WavRecoveryResult.Conflict>(WavWriter.recoverWithReport(source.path))
        assertTrue(Files.isSymbolicLink(targetLink.toPath())); assertContentEquals(before, real.readBytes())
    }
    @Test fun three_gibibyte_virtual_audio_uses_only_bounded_read_and_write_requests() {
        val io = VirtualLargeIo(3L * 1024 * 1024 * 1024)
        val result = assertIs<WavRecoveryResult.Recovered>(recoverJvmWav(io.source.toString(), 16_000, io))
        assertEquals(io.dataBytes + 44, result.finish.sizeBytes)
        assertTrue(io.maxRead <= WAV_RECOVERY_BUFFER_BYTES); assertTrue(io.maxWrite <= WAV_RECOVERY_BUFFER_BYTES)
        assertEquals(io.dataBytes + 44, io.written); assertTrue(io.published)
    }
    @Test fun riff_overflow_and_invalid_rate_are_rejected_without_allocating_the_audio() {
        val io = VirtualLargeIo(0x1_0000_0000L)
        assertIs<WavRecoveryResult.Invalid>(recoverJvmWav(io.source.toString(), 16_000, io)); assertEquals(0L, io.written)
        val small = VirtualLargeIo(100)
        assertIs<WavRecoveryResult.Invalid>(recoverJvmWav(small.source.toString(), 0, small)); assertEquals(0L, small.written)
    }

    private fun wave(dir: File, name: String, sample: Byte = 7, rate: Int = 16_000, stale: Boolean = false): File {
        val f = File(dir, name); WavWriter(f.path, rate).apply { writePcm(ByteArray(3200) { sample }) }.finish()
        if (stale) RandomAccessFile(f, "rw").use { raf -> raf.seek(4); raf.write(byteArrayOf(36, 0, 0, 0)); raf.seek(40); raf.write(ByteArray(4)) }
        return f
    }
    private fun unsignedInt(bytes: ByteArray, offset: Int) = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(offset).toLong() and 0xffff_ffffL
    private fun fixture(block: (File) -> Unit) { val dir = Files.createTempDirectory("velum-wav-engine-test").toFile(); try { block(dir) } finally { dir.deleteRecursively() } }

    private class VirtualLargeIo(val dataBytes: Long) : JvmWavRecoveryIo() {
        val source: Path = Paths.get("/synthetic/rec-large.wav.partial")
        private val stagePath: Path = Paths.get("/synthetic/.owned-stage.tmp")
        private val target = Paths.get("/synthetic/rec-large.wav")
        private val original = ByteArray(44).also { h ->
            fun text(offset: Int, s: String) { s.forEachIndexed { i, c -> h[offset + i] = c.code.toByte() } }
            text(0, "RIFF"); text(8, "WAVE"); text(12, "fmt "); text(36, "data")
            ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN).apply { putInt(4, 36); putInt(16, 16); putShort(20, 1); putShort(22, 1); putInt(24, 16_000); putInt(28, 32_000); putShort(32, 2); putShort(34, 16) }
        }
        private var stagedHeader = ByteArray(44)
        private var destinationHeader = ByteArray(44)
        private var destinationLength = 0L
        var written = 0L
        var maxRead = 0
        var maxWrite = 0
        var published = false
        private var stageExists = false
        override fun stamp(path: Path): RecoveryStamp? = when (path) {
            source -> RecoveryStamp(dataBytes + 44, 1, "source", true)
            stagePath -> if (stageExists) RecoveryStamp(written, 2, "stage", true) else null
            target -> if (published) RecoveryStamp(destinationLength, 3, "destination", true) else null
            else -> null
        }
        override fun reader(path: Path): RecoveryReader = object : RecoveryReader {
            var position = 0L
            override fun readFully(buffer: ByteArray, offset: Int, length: Int) {
                maxRead = maxOf(maxRead, length)
                java.util.Arrays.fill(buffer, offset, offset + length, 0.toByte())
                val header = when (path) { source -> original; target -> destinationHeader; else -> stagedHeader }
                if (position < 44) {
                    val count = minOf(length.toLong(), 44 - position).toInt()
                    header.copyInto(buffer, offset, position.toInt(), position.toInt() + count)
                }
                position += length
            }
            override fun close() = Unit
        }
        override fun stage(parent: Path): RecoveryStage {
            stageExists = true
            return object : RecoveryStage {
                override val path = stagePath
                override fun write(buffer: ByteArray, offset: Int, length: Int) {
                    maxWrite = maxOf(maxWrite, length)
                    if (written == 0L) stagedHeader = buffer.copyOfRange(offset, offset + length)
                    written += length
                }
                override fun sync() = Unit
                override fun close() = Unit
            }
        }
        override fun destination(target: Path): RecoveryDestination {
            check(!published); published = true
            return object : RecoveryDestination {
                private var position = 0L
                override fun position(offset: Long) { position = offset }
                override fun write(buffer: ByteArray, offset: Int, length: Int) {
                    maxWrite = maxOf(maxWrite, length)
                    if (position < 44) {
                        val n = minOf(length.toLong(), 44 - position).toInt()
                        buffer.copyInto(destinationHeader, position.toInt(), offset, offset + n)
                    }
                    position += length; destinationLength = maxOf(destinationLength, position)
                }
                override fun readFully(buffer: ByteArray, offset: Int, length: Int) {
                    check(position + length <= destinationLength)
                    maxRead = maxOf(maxRead, length); java.util.Arrays.fill(buffer, offset, offset + length, 0.toByte())
                    if (position < 44) {
                        val n = minOf(length.toLong(), 44 - position).toInt()
                        destinationHeader.copyInto(buffer, offset, position.toInt(), position.toInt() + n)
                    }
                    position += length
                }
                override fun length() = destinationLength
                override fun sync() = Unit
                override fun close() = Unit
            }
        }
        override fun discardStage(path: Path) { stageExists = false }
    }
}
