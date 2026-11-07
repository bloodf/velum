@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.velum.app.platform

import kotlinx.cinterop.*
import platform.posix.*

private const val HEADER = 44
private const val BUFFER = 64 * 1024
private class SourceChanged : Exception()
private class NativeIo : Exception()

private fun checked(result: Int) { if (result < 0) throw NativeIo() }
private fun syncFile(fd: Int) {
    while (fsync(fd) != 0) if (errno != EINTR) throw NativeIo()
}
private fun seek(fd: Int, offset: Long) { if (lseek(fd, offset, SEEK_SET) != offset) throw NativeIo() }
private fun transfer(fd: Int, bytes: ByteArray, count: Int = bytes.size, writing: Boolean) {
    if (count == 0) return
    bytes.usePinned { pinned ->
        var done = 0
        while (done < count) {
            val n = if (writing) write(fd, pinned.addressOf(done), (count - done).convert())
                else read(fd, pinned.addressOf(done), (count - done).convert())
            if (n < 0 && errno == EINTR) continue
            if (n <= 0) throw NativeIo()
            done += n.toInt()
        }
    }
}
private fun openExisting(path: String, writable: Boolean = false): Int {
    val fd = open(path, (if (writable) O_RDWR else O_RDONLY) or O_NOFOLLOW or O_NONBLOCK)
    if (fd < 0) throw NativeIo()
    return fd
}
private data class Stamp(val device: ULong, val inode: ULong, val size: Long, val seconds: Long, val nanos: Long)
private fun stamp(fd: Int): Stamp = memScoped {
    val s = alloc<stat>()
    checked(fstat(fd, s.ptr))
    if ((s.st_mode.toInt() and S_IFMT) != S_IFREG) throw NativeIo()
    Stamp(s.st_dev.toULong(), s.st_ino.toULong(), s.st_size, s.st_mtimespec.tv_sec, s.st_mtimespec.tv_nsec)
}
private fun exists(path: String): Boolean = memScoped {
    val s = alloc<stat>()
    if (lstat(path, s.ptr) == 0) true else if (errno == ENOENT) false else throw NativeIo()
}
private fun header(data: Long, rate: Int): ByteArray {
    require(rate > 0 && rate <= Int.MAX_VALUE / 2)
    require(data in 0..(0xffff_ffffL - 36) && data % 2 == 0L)
    return ByteArray(HEADER).also { b ->
        fun text(at: Int, s: String) { s.forEachIndexed { i, c -> b[at + i] = c.code.toByte() } }
        fun number(at: Int, n: Long, width: Int = 4) { repeat(width) { b[at + it] = (n ushr (it * 8)).toByte() } }
        text(0, "RIFF"); text(8, "WAVE"); text(12, "fmt "); text(36, "data")
        number(4, data + 36); number(16, 16); number(20, 1, 2); number(22, 1, 2)
        number(24, rate.toLong()); number(28, rate * 2L); number(32, 2, 2); number(34, 16, 2); number(40, data)
    }
}

internal actual fun openWavFile(absolutePath: String): WavFileHandle {
    // Exclusive creation prevents a second writer from truncating an existing recording.
    val fd = open(absolutePath, O_CREAT or O_EXCL or O_RDWR or O_NOFOLLOW, 384)
    if (fd < 0) throw NativeIo()
    return object : WavFileHandle {
        private var closed = false
        private var size = 0L
        override fun writeWavHeader(dataSize: Long, sampleRateHz: Int) {
            check(!closed)
            val bytes = header(dataSize, sampleRateHz)
            seek(fd, 0); transfer(fd, bytes, writing = true)
        }
        override fun append(bytes: ByteArray) {
            check(!closed)
            val length = stamp(fd).size
            require(length + bytes.size - HEADER <= 0xffff_ffffL - 36)
            seek(fd, length.coerceAtLeast(HEADER.toLong())); transfer(fd, bytes, writing = true)
        }
        override fun close() {
            if (closed) return
            try { syncFile(fd); size = stamp(fd).size } finally {
                closed = true
                checked(platform.posix.close(fd))
            }
        }
        override fun length(): Long = if (closed) size else stamp(fd).size
    }
}

private data class Inspected(val stamp: Stamp, val original: ByteArray, val canonical: ByteArray)
private fun inspect(fd: Int, rate: Int): Inspected? {
    val before = stamp(fd)
    val data = before.size - HEADER
    if (data <= 0 || data % 2 != 0L || data > 0xffff_ffffL - 36 || rate <= 0 || rate > Int.MAX_VALUE / 2) return null
    val original = ByteArray(HEADER)
    seek(fd, 0); transfer(fd, original, writing = false)
    if (stamp(fd) != before) throw SourceChanged()
    val canonical = header(data, rate)
    if (original.indices.any { it !in 4..7 && it !in 40..43 && original[it] != canonical[it] }) return null
    return Inspected(before, original, canonical)
}
private fun unchanged(fd: Int, path: String, expected: Stamp) {
    if (stamp(fd) != expected) throw SourceChanged()
    val current = openExisting(path)
    try { if (stamp(current) != expected) throw SourceChanged() } finally { close(current) }
}
private fun sameAudio(a: Int, av: Inspected, b: Int, bv: Inspected): Boolean {
    if (av.stamp.size != bv.stamp.size || !av.canonical.contentEquals(bv.canonical)) return false
    seek(a, HEADER.toLong()); seek(b, HEADER.toLong())
    val left = ByteArray(BUFFER); val right = ByteArray(BUFFER)
    var remaining = av.stamp.size - HEADER
    while (remaining > 0) {
        val n = minOf(remaining, BUFFER.toLong()).toInt()
        transfer(a, left, n, false); transfer(b, right, n, false)
        if ((0 until n).any { left[it] != right[it] }) return false
        remaining -= n
    }
    if (stamp(a) != av.stamp || stamp(b) != bv.stamp) throw SourceChanged()
    return true
}
private fun repair(fd: Int, path: String, wav: Inspected) {
    if (wav.original.contentEquals(wav.canonical)) return
    checked(flock(fd, LOCK_EX or LOCK_NB))
    unchanged(fd, path, wav.stamp)
    val check = ByteArray(HEADER)
    seek(fd, 0); transfer(fd, check, writing = false)
    if (!check.contentEquals(wav.original)) throw SourceChanged()
    for (offset in intArrayOf(4, 40)) {
        seek(fd, offset.toLong()); transfer(fd, wav.canonical.copyOfRange(offset, offset + 4), writing = true)
    }
    syncFile(fd)
    val after = inspect(fd, ((wav.canonical[24].toInt() and 255) or
        ((wav.canonical[25].toInt() and 255) shl 8) or ((wav.canonical[26].toInt() and 255) shl 16) or
        ((wav.canonical[27].toInt() and 255) shl 24))) ?: throw SourceChanged()
    if (!after.original.contentEquals(wav.canonical) || after.stamp.size != wav.stamp.size) throw SourceChanged()
    unchanged(fd, path, after.stamp)
}

internal actual fun recoverOrFinalizeWav(absolutePath: String, sampleRateHz: Int): WavFinish? =
    recoverDetailedWav(absolutePath, sampleRateHz).finishedOrNull()

internal actual fun recoverDetailedWav(absolutePath: String, sampleRateHz: Int): WavRecoveryResult {
    val partial = absolutePath.endsWith(".partial", ignoreCase = true)
    val target = if (partial) absolutePath.dropLast(8) else absolutePath
    var operation = WavRecoveryOperation.Read
    var source = -1
    var stage = -1
    var stagePath: String? = null
    try {
        if (!exists(absolutePath)) {
            if (partial && exists(target)) {
                val result = recoverDetailedWav(target, sampleRateHz)
                if (result is WavRecoveryResult.Recovered) return result.copy(sourcePath = absolutePath, reusedFinal = true)
            }
            return WavRecoveryResult.Missing(absolutePath)
        }
        source = openExisting(absolutePath, writable = !partial)
        checked(flock(source, LOCK_EX or LOCK_NB))
        val wav = inspect(source, sampleRateHz) ?: return WavRecoveryResult.Invalid(absolutePath)
        fun finish() = WavFinish(target, WavWriter.durationSeconds(wav.stamp.size - HEADER, sampleRateHz), wav.stamp.size)
        if (!partial) {
            operation = WavRecoveryOperation.RepairHeader
            repair(source, absolutePath, wav)
            return WavRecoveryResult.Recovered(absolutePath, finish())
        }
        if (exists(target)) {
            val existing = openExisting(target, writable = true)
            try {
                checked(flock(existing, LOCK_EX or LOCK_NB))
                val final = inspect(existing, sampleRateHz)
                if (final == null || !sameAudio(source, wav, existing, final)) return WavRecoveryResult.Conflict(absolutePath, target)
                unchanged(source, absolutePath, wav.stamp)
                operation = WavRecoveryOperation.RepairHeader
                repair(existing, target, final)
                return WavRecoveryResult.Recovered(absolutePath, finish(), partialPreserved = true, reusedFinal = true)
            } finally { close(existing) }
        }
        operation = WavRecoveryOperation.Copy
        val template = (target + ".recovery-XXXXXX").encodeToByteArray() + byteArrayOf(0)
        template.usePinned { pinned ->
            stage = mkstemp(pinned.addressOf(0))
            if (stage < 0) throw NativeIo()
            stagePath = pinned.addressOf(0).toKString()
        }
        transfer(stage, wav.canonical, writing = true)
        seek(source, HEADER.toLong())
        val buffer = ByteArray(BUFFER)
        var remaining = wav.stamp.size - HEADER
        while (remaining > 0) {
            val n = minOf(remaining, BUFFER.toLong()).toInt()
            transfer(source, buffer, n, false); transfer(stage, buffer, n, true); remaining -= n
        }
        syncFile(stage)
        operation = WavRecoveryOperation.VerifyCopy
        val staged = inspect(stage, sampleRateHz) ?: throw NativeIo()
        if (!sameAudio(source, wav, stage, staged)) throw NativeIo()
        unchanged(source, absolutePath, wav.stamp)
        operation = WavRecoveryOperation.Publish
        // link is atomic and fails if any destination already exists.
        checked(link(checkNotNull(stagePath), target))
        val directory = open(target.substringBeforeLast('/', "."), O_RDONLY)
        if (directory < 0) throw NativeIo()
        try { syncFile(directory) } finally { close(directory) }
        return WavRecoveryResult.Recovered(absolutePath, finish(), partialPreserved = true)
    } catch (_: SourceChanged) {
        return WavRecoveryResult.Changed(absolutePath)
    } catch (_: Exception) {
        if (operation == WavRecoveryOperation.Publish && runCatching { exists(target) }.getOrDefault(false)) {
            return WavRecoveryResult.Conflict(absolutePath, target)
        }
        return WavRecoveryResult.Unavailable(absolutePath, operation)
    } finally {
        if (source >= 0) close(source)
        if (stage >= 0) close(stage)
        stagePath?.let { unlink(it) } // Only this invocation's exclusive scratch file.
    }
}

internal actual fun inspectMicDraftAudio(absolutePath: String): MicDraftAudioEvidence = MicDraftAudioEvidence.Unavailable
