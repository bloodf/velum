package dev.velum.ble.client

import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets

/**
 * Atomic file-backed [SessionDownloadProgressStore].
 * Each checkpoint is written to `*.tmp` then renamed over the live file.
 */
class FileSessionDownloadProgressStore(
    private val directory: File,
) : SessionDownloadProgressStore {
    init {
        directory.mkdirs()
    }

    override fun load(sessionId: Long): SessionDownloadCheckpoint? {
        val file = fileFor(sessionId)
        if (!file.isFile) return null
        val text = file.readText(StandardCharsets.UTF_8)
        return parse(text)
    }

    override fun save(checkpoint: SessionDownloadCheckpoint) {
        directory.mkdirs()
        val target = fileFor(checkpoint.sessionId)
        val tmp = File(target.parentFile, "${target.name}.tmp")
        val bytes = serialize(checkpoint).toByteArray(StandardCharsets.UTF_8)
        RandomAccessFile(tmp, "rw").use { raf ->
            raf.setLength(0)
            raf.write(bytes)
            raf.fd.sync()
        }
        if (!tmp.renameTo(target)) {
            target.delete()
            check(tmp.renameTo(target)) { "Failed to atomically replace ${target.path}" }
        }
    }

    override fun clear(sessionId: Long) {
        fileFor(sessionId).delete()
        File(directory, "session-$sessionId.ckpt.tmp").delete()
    }

    private fun fileFor(sessionId: Long): File = File(directory, "session-$sessionId.ckpt")

    companion object {
        fun serialize(checkpoint: SessionDownloadCheckpoint): String =
            buildString {
                append("v1\n")
                append("sessionId=").append(checkpoint.sessionId).append('\n')
                append("expectedSizeBytes=").append(checkpoint.expectedSizeBytes).append('\n')
                append("verifiedOffset=").append(checkpoint.verifiedOffset).append('\n')
                append("runningCrc=").append(checkpoint.runningCrc).append('\n')
                append("partialPath=").append(checkpoint.partialPath).append('\n')
                append("status=").append(checkpoint.status.name).append('\n')
            }

        fun parse(text: String): SessionDownloadCheckpoint {
            val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
            require(lines.isNotEmpty() && lines[0] == "v1") { "Unsupported checkpoint format" }
            val map = mutableMapOf<String, String>()
            for (i in 1 until lines.size) {
                val idx = lines[i].indexOf('=')
                require(idx > 0) { "Bad checkpoint line: ${lines[i]}" }
                map[lines[i].substring(0, idx)] = lines[i].substring(idx + 1)
            }
            return SessionDownloadCheckpoint(
                sessionId = map.getValue("sessionId").toLong(),
                expectedSizeBytes = map.getValue("expectedSizeBytes").toLong(),
                verifiedOffset = map.getValue("verifiedOffset").toLong(),
                runningCrc = map.getValue("runningCrc").toInt(),
                partialPath = map.getValue("partialPath"),
                status = SessionDownloadStatus.valueOf(map.getValue("status")),
            )
        }
    }
}
