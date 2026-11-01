package dev.velum.app.platform

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes
import java.io.RandomAccessFile
import kotlinx.coroutines.CancellationException

internal actual fun inspectMicDraftAudio(absolutePath: String): MicDraftAudioEvidence = inspectJvmMicDraftAudio(absolutePath)

/** Read-only boundary; injected failures never change real permissions or user files. */
internal open class MicDraftFileIo {
    open fun attributes(path: Path): BasicFileAttributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    open fun children(path: Path): List<Path> = Files.newDirectoryStream(path).use { it.toList() }
    open fun header(path: Path): ByteArray = RandomAccessFile(path.toFile(), "r").use { file -> ByteArray(44).also { file.readFully(it) } }
}

internal fun inspectJvmMicDraftAudio(path: String, io: MicDraftFileIo = MicDraftFileIo()): MicDraftAudioEvidence {
    val target = java.io.File(path).absoluteFile.toPath().normalize()
    try {
        val parent = try { io.attributes(target.parent) } catch (_: java.nio.file.NoSuchFileException) { return MicDraftAudioEvidence.MissingOrEmpty }
        if (!parent.isDirectory || parent.isSymbolicLink) return MicDraftAudioEvidence.Unavailable
        val name = target.fileName.toString()
        val family = io.children(target.parent).filter { it.fileName.toString().equals(name, true) || it.fileName.toString().equals("$name.partial", true) }
        for (file in family) {
            val before = io.attributes(file)
            if (!before.isRegularFile || before.isSymbolicLink) return MicDraftAudioEvidence.Unavailable
            if (before.size() != 0L && before.size() != 44L) return MicDraftAudioEvidence.Present
            val header = if (before.size() == 44L) io.header(file) else null
            val after = io.attributes(file)
            if (before.size() != after.size() || before.fileKey() != after.fileKey() || before.lastModifiedTime() != after.lastModifiedTime()) return MicDraftAudioEvidence.Unavailable
            if (header != null && !header.contentEquals(pcm16MonoWavHeader(0, 16_000))) return MicDraftAudioEvidence.Present
        }
        val after = io.attributes(target.parent)
        if (parent.fileKey() != after.fileKey() || parent.lastModifiedTime() != after.lastModifiedTime()) return MicDraftAudioEvidence.Unavailable
        return MicDraftAudioEvidence.MissingOrEmpty
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { return MicDraftAudioEvidence.Unavailable }
}
