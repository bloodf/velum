package dev.velum.app.platform

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Deletes [dir] and what is in it. A symlink is removed, never followed, so what it points at is left alone
 * (`File.deleteRecursively` would delete it). False if anything remains.
 */
internal fun deleteTreeWithoutFollowingLinks(dir: File): Boolean {
    val root = dir.toPath()
    if (!Files.exists(root, NOFOLLOW_LINKS)) return true
    runCatching {
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                runCatching { Files.delete(file) }
                return FileVisitResult.CONTINUE
            }

            // One entry that can't be read must not stop the rest; the result below reports what remains.
            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE

            override fun postVisitDirectory(directory: Path, exc: IOException?): FileVisitResult {
                runCatching { Files.delete(directory) }
                return FileVisitResult.CONTINUE
            }
        })
    }
    return !Files.exists(root, NOFOLLOW_LINKS)
}
