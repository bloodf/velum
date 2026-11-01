package dev.velum.app.platform

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.channels.FileChannel
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.CancellationException

internal actual fun recoverDetailedWav(absolutePath: String, sampleRateHz: Int): WavRecoveryResult =
    recoverJvmWav(absolutePath, sampleRateHz, JvmWavRecoveryIo())

internal data class RecoveryStamp(val length: Long, val modified: Any, val key: Any?, val regular: Boolean)
internal class WavSourceChanged : Exception()
internal interface RecoveryReader : AutoCloseable {
    fun readFully(buffer: ByteArray, offset: Int, length: Int)
}
internal interface RecoveryDestination : AutoCloseable {
    fun position(offset: Long)
    fun write(buffer: ByteArray, offset: Int, length: Int)
    fun readFully(buffer: ByteArray, offset: Int, length: Int)
    fun length(): Long
    fun sync()
}
internal interface RecoveryStage : AutoCloseable {
    val path: Path
    fun write(buffer: ByteArray, offset: Int, length: Int)
    fun sync()
}

/** Narrow filesystem boundary for deterministic failures and virtual large-file tests. */
internal open class JvmWavRecoveryIo {
    open fun stamp(path: Path): RecoveryStamp? {
        val a = try { Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS) }
        catch (_: java.nio.file.NoSuchFileException) { return null }
        return RecoveryStamp(a.size(), a.lastModifiedTime(), a.fileKey(), a.isRegularFile)
    }
    open fun reader(path: Path): RecoveryReader {
        val raf = RandomAccessFile(path.toFile(), "r")
        return object : RecoveryReader {
            override fun readFully(buffer: ByteArray, offset: Int, length: Int) = raf.readFully(buffer, offset, length)
            override fun close() = raf.close()
        }
    }
    open fun stage(parent: Path): RecoveryStage {
        val path = Files.createTempFile(parent, ".velum-wav-recovery-", ".tmp")
        val raf = try { RandomAccessFile(path.toFile(), "rw") } catch (failure: Exception) {
            Files.deleteIfExists(path); throw failure
        }
        return object : RecoveryStage {
            override val path: Path = path
            override fun write(buffer: ByteArray, offset: Int, length: Int) = raf.write(buffer, offset, length)
            override fun sync() = raf.fd.sync()
            override fun close() = raf.close()
        }
    }
    /** CREATE_NEW is the atomic reservation: an existing destination is never opened for writing. */
    open fun destination(target: Path): RecoveryDestination {
        val channel = FileChannel.open(target, CREATE_NEW, READ, WRITE)
        return object : RecoveryDestination {
            override fun position(offset: Long) { channel.position(offset) }
            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                val bytes = ByteBuffer.wrap(buffer, offset, length)
                while (bytes.hasRemaining()) if (channel.write(bytes) <= 0) throw java.io.IOException("Incomplete WAV publication write.")
            }
            override fun readFully(buffer: ByteArray, offset: Int, length: Int) {
                val bytes = ByteBuffer.wrap(buffer, offset, length)
                while (bytes.hasRemaining()) if (channel.read(bytes) <= 0) throw WavSourceChanged()
            }
            override fun length() = channel.size()
            override fun sync() = channel.force(true)
            override fun close() = channel.close()
        }
    }
    /**
     * Android's sandbox forbids hard links. Publish through a new exclusive descriptor,
     * leaving an invalid header until all PCM has been copied, synced and compared.
     * A failed publication keeps its incomplete destination; it is never guessed disposable.
     */
    open fun publish(stage: Path, target: Path) {
        val expected = stamp(stage) ?: throw WavSourceChanged()
        reader(stage).use { input ->
            val header = ByteArray(WAV_HEADER_BYTES); input.readFully(header, 0, header.size)
            val rate = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getInt(24)
            if (canonicalHeader(header, expected.length, rate)?.contentEquals(header) != true) throw WavSourceChanged()
            destination(target).use { output ->
                output.write(ByteArray(WAV_HEADER_BYTES), 0, WAV_HEADER_BYTES)
                val left = ByteArray(WAV_RECOVERY_BUFFER_BYTES); val right = ByteArray(WAV_RECOVERY_BUFFER_BYTES)
                var remaining = expected.length - WAV_HEADER_BYTES
                while (remaining > 0) {
                    val n = minOf(remaining, left.size.toLong()).toInt()
                    input.readFully(left, 0, n); output.write(left, 0, n); remaining -= n
                }
                output.sync()
                reader(stage).use { verification ->
                    val checkHeader = ByteArray(WAV_HEADER_BYTES); verification.readFully(checkHeader, 0, checkHeader.size)
                    if (!checkHeader.contentEquals(header)) throw WavSourceChanged()
                    output.position(WAV_HEADER_BYTES.toLong())
                    // Reset the unused tails before the full-buffer comparisons below.
                    left.fill(0); right.fill(0)
                    remaining = expected.length - WAV_HEADER_BYTES
                    while (remaining > 0) {
                        val n = minOf(remaining, left.size.toLong()).toInt()
                        verification.readFully(left, 0, n); output.readFully(right, 0, n)
                        if (!left.contentEquals(right)) throw java.io.IOException("WAV publication verification failed.")
                        remaining -= n
                    }
                }
                if (stamp(stage) != expected || output.length() != expected.length) throw WavSourceChanged()
                output.position(0); output.write(header, 0, header.size); output.sync()
                output.position(0); val check = ByteArray(WAV_HEADER_BYTES); output.readFully(check, 0, check.size)
                if (!check.contentEquals(header)) throw WavSourceChanged()
            }
        }
    }
    open fun discardStage(path: Path) { Files.deleteIfExists(path) }

    /** Only the two stale size fields change. An interrupted repair never touches PCM/format bytes. */
    open fun repairSizes(path: Path, expected: RecoveryStamp, original: ByteArray, canonical: ByteArray) {
        if (stamp(path) != expected) throw WavSourceChanged()
        FileChannel.open(path, READ, WRITE, NOFOLLOW_LINKS).use { channel ->
            val lock = channel.tryLock() ?: throw WavSourceChanged()
            try {
                val header = ByteArray(WAV_HEADER_BYTES)
                fun readHeader() {
                    channel.position(0)
                    val buffer = ByteBuffer.wrap(header)
                    while (buffer.hasRemaining()) if (channel.read(buffer) <= 0) throw WavSourceChanged()
                }
                readHeader()
                if (!header.contentEquals(original) || channel.size() != expected.length || stamp(path) != expected) throw WavSourceChanged()
                for (offset in intArrayOf(4, 40)) {
                    channel.position(offset.toLong())
                    val buffer = ByteBuffer.wrap(canonical, offset, 4)
                    while (buffer.hasRemaining()) if (channel.write(buffer) <= 0) throw java.io.IOException("Incomplete WAV size-field write.")
                }
                channel.force(true)
                readHeader()
                val after = stamp(path)
                if (!header.contentEquals(canonical) || after == null || after.key != expected.key || after.length != expected.length || !after.regular) throw WavSourceChanged()
            } finally { lock.release() }
        }
    }
}

internal const val WAV_HEADER_BYTES = 44
internal const val WAV_RECOVERY_BUFFER_BYTES = 64 * 1024
private data class InspectedWav(val stamp: RecoveryStamp, val original: ByteArray, val canonical: ByteArray, val dataBytes: Long)

/** Fixed-format PCM16 mono WAVs written by this app, not arbitrary imported containers. */
private fun canonicalHeader(original: ByteArray, length: Long, rate: Int): ByteArray? {
    if (original.size != WAV_HEADER_BYTES || rate <= 0 || rate > Int.MAX_VALUE / 2) return null
    val data = length - WAV_HEADER_BYTES
    if (data <= 0 || data % 2L != 0L || data + 36L > 0xffff_ffffL) return null
    fun magic(offset: Int, value: String) = value.indices.all { original[offset + it] == value[it].code.toByte() }
    val b = ByteBuffer.wrap(original).order(ByteOrder.LITTLE_ENDIAN)
    if (!magic(0, "RIFF") || !magic(8, "WAVE") || !magic(12, "fmt ") || !magic(36, "data") ||
        b.getInt(16) != 16 || b.getShort(20).toInt() != 1 || b.getShort(22).toInt() != 1 ||
        b.getInt(24) != rate || b.getInt(28) != rate * 2 || b.getShort(32).toInt() != 2 || b.getShort(34).toInt() != 16) return null
    return original.copyOf().also {
        val fixed = ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN)
        fixed.putInt(4, (data + 36).toInt()); fixed.putInt(40, data.toInt())
    }
}

private fun inspect(path: Path, rate: Int, io: JvmWavRecoveryIo): InspectedWav? {
    val before = io.stamp(path) ?: return null
    if (!before.regular || before.length <= WAV_HEADER_BYTES) return null
    val header = ByteArray(WAV_HEADER_BYTES)
    io.reader(path).use { it.readFully(header, 0, header.size) }
    if (io.stamp(path) != before) throw WavSourceChanged()
    val canonical = canonicalHeader(header, before.length, rate) ?: return null
    return InspectedWav(before, header, canonical, before.length - WAV_HEADER_BYTES)
}

/** Full byte comparison, bounded to two reusable buffers; no digest collision or whole-file read. */
private fun sameAudio(a: Path, av: InspectedWav, b: Path, bv: InspectedWav, io: JvmWavRecoveryIo, requireCanonical: Boolean = true): Boolean {
    if (av.dataBytes != bv.dataBytes || !av.canonical.contentEquals(bv.canonical) || (requireCanonical && !bv.original.contentEquals(bv.canonical))) return false
    val left = ByteArray(WAV_RECOVERY_BUFFER_BYTES); val right = ByteArray(WAV_RECOVERY_BUFFER_BYTES)
    val equal = io.reader(a).use { x -> io.reader(b).use compare@ { y ->
        val ah = ByteArray(WAV_HEADER_BYTES); val bh = ByteArray(WAV_HEADER_BYTES)
        x.readFully(ah, 0, ah.size); y.readFully(bh, 0, bh.size)
        if (!ah.contentEquals(av.original) || !bh.contentEquals(bv.original)) throw WavSourceChanged()
        var remaining = av.dataBytes
        while (remaining > 0) {
            val n = minOf(remaining, left.size.toLong()).toInt()
            x.readFully(left, 0, n); y.readFully(right, 0, n)
            // Unused tails are equal from the preceding equal block (or initial zeroes).
            if (!left.contentEquals(right)) return@compare false
            remaining -= n
        }
        true
    } }
    if (io.stamp(a) != av.stamp || io.stamp(b) != bv.stamp) throw WavSourceChanged()
    return equal
}

internal fun recoverJvmWav(path: String, rate: Int, io: JvmWavRecoveryIo): WavRecoveryResult {
    val source = File(path).absoluteFile.toPath().normalize()
    val sourcePath = source.toString()
    val partial = source.fileName.toString().endsWith(".partial", ignoreCase = true)
    val target = if (partial) source.resolveSibling(source.fileName.toString().dropLast(".partial".length)) else source
    var operation = WavRecoveryOperation.Read
    var staging: RecoveryStage? = null
    try {
        if (io.stamp(source) == null) {
            // Idempotent retry after a legacy rename completed before a crash.
            if (partial && io.stamp(target) != null) {
                return when (val existing = recoverJvmWav(target.toString(), rate, io)) {
                    is WavRecoveryResult.Recovered -> existing.copy(sourcePath = sourcePath, reusedFinal = true)
                    is WavRecoveryResult.Unavailable -> existing.copy(sourcePath = sourcePath)
                    is WavRecoveryResult.Changed -> WavRecoveryResult.Changed(sourcePath)
                    else -> WavRecoveryResult.Missing(sourcePath)
                }
            }
            return WavRecoveryResult.Missing(sourcePath)
        }
        val wav = inspect(source, rate, io) ?: return WavRecoveryResult.Invalid(sourcePath)
        fun finish(p: Path) = WavFinish(p.toString(), WavWriter.durationSeconds(wav.dataBytes, rate), wav.stamp.length)
        if (!partial) {
            if (!wav.original.contentEquals(wav.canonical)) {
                operation = WavRecoveryOperation.RepairHeader
                io.repairSizes(source, wav.stamp, wav.original, wav.canonical)
            }
            return WavRecoveryResult.Recovered(sourcePath, finish(source))
        }
        if (io.stamp(target) != null) {
            val existing = inspect(target, rate, io)
            if (existing != null && sameAudio(source, wav, target, existing, io, requireCanonical = false)) {
                if (!existing.original.contentEquals(existing.canonical)) {
                    operation = WavRecoveryOperation.RepairHeader
                    io.repairSizes(target, existing.stamp, existing.original, existing.canonical)
                }
                return WavRecoveryResult.Recovered(sourcePath, finish(target), partialPreserved = true, reusedFinal = true)
            }
            return WavRecoveryResult.Conflict(sourcePath, target.toString())
        }
        operation = WavRecoveryOperation.Copy
        val stage = io.stage(source.parent).also { staging = it }
        io.reader(source).use { reader ->
            val header = ByteArray(WAV_HEADER_BYTES); reader.readFully(header, 0, header.size)
            if (!header.contentEquals(wav.original)) throw WavSourceChanged()
            stage.write(wav.canonical, 0, wav.canonical.size)
            val buffer = ByteArray(WAV_RECOVERY_BUFFER_BYTES)
            var remaining = wav.dataBytes
            while (remaining > 0) {
                val n = minOf(remaining, buffer.size.toLong()).toInt()
                reader.readFully(buffer, 0, n); stage.write(buffer, 0, n); remaining -= n
            }
        }
        stage.sync(); stage.close()
        if (io.stamp(source) != wav.stamp) throw WavSourceChanged()
        operation = WavRecoveryOperation.VerifyCopy
        val stagedWav = inspect(stage.path, rate, io) ?: return WavRecoveryResult.Unavailable(sourcePath, operation)
        if (!sameAudio(source, wav, stage.path, stagedWav, io)) return WavRecoveryResult.Unavailable(sourcePath, operation)
        operation = WavRecoveryOperation.Publish
        io.publish(stage.path, target)
        val published = inspect(target, rate, io) ?: return WavRecoveryResult.Conflict(sourcePath, target.toString())
        if (!sameAudio(stage.path, stagedWav, target, published, io)) return WavRecoveryResult.Conflict(sourcePath, target.toString())
        return WavRecoveryResult.Recovered(sourcePath, finish(target), partialPreserved = true)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: WavSourceChanged) {
        return WavRecoveryResult.Changed(sourcePath)
    } catch (_: Exception) {
        // A target created by a racing writer is never replaced or removed.
        val targetPresent = if (operation == WavRecoveryOperation.Publish) {
            try { io.stamp(target) != null } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { false }
        } else false
        if (targetPresent) {
            return WavRecoveryResult.Conflict(sourcePath, target.toString())
        }
        return WavRecoveryResult.Unavailable(sourcePath, operation)
    } finally {
        staging?.let {
            try { it.close() } catch (_: Exception) { }
            // This is our exclusively-created scratch entry, never source or final.
            try { io.discardStage(it.path) } catch (_: Exception) { /* Safe residue after cleanup failure. */ }
        }
    }
}

/** The app's fixed PCM16 mono header, shared by both writers and exclusive microphone creation. */
internal fun pcm16MonoWavHeader(dataSize: Long, sampleRateHz: Int): ByteArray = ByteArray(44).also { header ->
    fun text(offset: Int, value: String) { value.forEachIndexed { index, c -> header[offset + index] = c.code.toByte() } }
    text(0, "RIFF"); text(8, "WAVE"); text(12, "fmt "); text(36, "data")
    ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).apply {
        putInt(4, (dataSize + 36).toInt()); putInt(16, 16); putShort(20, 1); putShort(22, 1)
        putInt(24, sampleRateHz); putInt(28, sampleRateHz * 2); putShort(32, 2); putShort(34, 16); putInt(40, dataSize.toInt())
    }
}
