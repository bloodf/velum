@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.velum.app.platform

import kotlinx.cinterop.*
import kotlinx.coroutines.CancellationException
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.posix.*
import kotlin.test.*

class DownloadPartFilesIosTest {
    private fun inDirectory(test: (String) -> Unit) {
        val directory = NSTemporaryDirectory() + NSUUID().UUIDString
        assertTrue(NSFileManager.defaultManager.createDirectoryAtPath(directory, true, null, null))
        try { test(directory) } finally {
            NSFileManager.defaultManager.removeItemAtPath(directory, null)
        }
    }

    private fun read(path: String): ByteArray {
        val fd = open(path, O_RDONLY)
        assertTrue(fd >= 0)
        try {
            val bytes = ByteArray(100)
            val count = bytes.usePinned { platform.posix.read(fd, it.addressOf(0), bytes.size.toULong()) }
            assertTrue(count >= 0)
            return bytes.copyOf(count.toInt())
        } finally { close(fd) }
    }

    private fun exists(path: String) = NSFileManager.defaultManager.fileExistsAtPath(path)

    @Test fun successCapturesBoundedHeadAcrossChunks() = inDirectory { dir ->
        val part = "$dir/rec-success.part"
        val target = "$dir/rec-success.wav"
        val result = streamToFileAtomically(part, 3, {
            assertContentEquals(byteArrayOf(1, 2, 3), it)
            assertFalse(exists(target))
            target
        }) { sink -> sink(byteArrayOf(1)); sink(byteArrayOf()); sink(byteArrayOf(2, 3, 4)) }
        assertEquals(target to 4L, result)
        assertContentEquals(byteArrayOf(1, 2, 3, 4), read(target))
        assertFalse(exists(part))
    }

    @Test fun emptyAndShortHeads() = inDirectory { dir ->
        for (bytes in listOf(byteArrayOf(), byteArrayOf(7), byteArrayOf(7, 8))) {
            val target = "$dir/result-${bytes.size}"
            assertEquals(target to bytes.size.toLong(), streamToFileAtomically("$target.part", 12, {
                assertContentEquals(bytes, it)
                target
            }) { it(bytes) })
            assertContentEquals(bytes, read(target))
        }
        streamToFileAtomically("$dir/zero.part", 0, {
            assertTrue(it.isEmpty()); "$dir/zero"
        }) { it(byteArrayOf(9)) }
    }

    @Test fun exceptionsAndCancellationRemoveOnlyOwnedPart() = inDirectory { dir ->
        val failures = listOf(IllegalStateException("producer"), CancellationException("cancel"))
        for (failure in failures) {
            val part = "$dir/rec-error.part"
            val caught = assertFails {
                streamToFileAtomically(part, 12, { "$dir/final" }) { sink ->
                    sink(byteArrayOf(1)); throw failure
                }
            }
            assertSame(failure, caught)
            assertFalse(exists(part))
            assertFalse(exists("$dir/final"))
        }
        assertFails {
            streamToFileAtomically("$dir/rec-error.part", 1, { error("head callback") }) { it(byteArrayOf(1)) }
        }
        assertFalse(exists("$dir/rec-error.part"))
        assertFails {
            streamToFileAtomically("$dir/rec-publish.part", 1, { "$dir/missing/final" }) { it(byteArrayOf(1)) }
        }
        assertFalse(exists("$dir/rec-publish.part"))
    }

    @Test fun replacementPreservesOldBytesUntilCommitAndOnFailure() = inDirectory { dir ->
        val target = "$dir/final"
        streamToFileAtomically("$dir/seed.part", 1, { target }) { it(byteArrayOf(42)) }
        assertFails {
            streamToFileAtomically("$dir/rec-error.part", 1, { target }) {
                it(byteArrayOf(9)); error("producer failed")
            }
        }
        assertContentEquals(byteArrayOf(42), read(target))
        assertFalse(exists("$dir/rec-error.part"))
        assertEquals(target to 1L, streamToFileAtomically("$dir/rec-replace.part", 1, {
            assertContentEquals(byteArrayOf(42), read(target))
            target
        }) { it(byteArrayOf(9)) })
        assertContentEquals(byteArrayOf(9), read(target))
        assertFalse(exists("$dir/rec-replace.part"))
        assertFails { streamToFileAtomically(target, 1, { "$dir/other" }) { error("must not produce") } }
        assertContentEquals(byteArrayOf(9), read(target))
        assertEquals(0, symlink(target, "$dir/link"))
        assertFails { streamToFileAtomically("$dir/rec-link.part", 1, { "$dir/link" }) { it(byteArrayOf(8)) } }
        assertContentEquals(byteArrayOf(9), read(target))
        assertFalse(exists("$dir/rec-link.part"))
    }

    @Test fun postPublicationSyncFailureStillReportsSuccessAndAllowsCallerCleanup() = inDirectory { dir ->
        val part = "$dir/rec-ready.part"
        val target = "$dir/final"
        streamToFileAtomically("$dir/seed.part", 1, { part }) { it(byteArrayOf(7)) }
        var callerPath: String? = null
        publishDownloadPart(part, target) {
            assertContentEquals(byteArrayOf(7), read(target))
            error("directory sync failed after commit")
        }.also { callerPath = target }
        assertEquals(target, callerPath)
        assertFalse(exists(part))
        assertContentEquals(byteArrayOf(7), read(target))
        assertEquals(0, unlink(requireNotNull(callerPath)))
        assertFalse(exists(target))
    }

    @Test fun postPublicationFailureNeverRollsBackAnotherWritersReplacement() = inDirectory { dir ->
        val part = "$dir/rec-ready.part"
        val target = "$dir/final"
        streamToFileAtomically("$dir/seed.part", 1, { part }) { it(byteArrayOf(7)) }
        publishDownloadPart(part, target) {
            streamToFileAtomically("$dir/rec-other.part", 1, { target }) { sink -> sink(byteArrayOf(8)) }
            error("directory sync failed after another writer committed")
        }
        assertContentEquals(byteArrayOf(8), read(target))
        assertFalse(exists(part))
        assertFalse(exists("$dir/rec-other.part"))
    }

    @Test fun sweepPreservesLivePartsSymlinksAndUnmatchedFiles() = inDirectory { dir ->
        for (name in listOf("rec-stale.part", "rec-fresh.part", "user.part")) {
            val fd = open("$dir/$name", O_CREAT or O_WRONLY, 384)
            assertTrue(fd >= 0)
            close(fd)
        }
        memScoped {
            val old = allocArray<timeval>(2)
            old[0].tv_sec = 1; old[0].tv_usec = 0
            old[1].tv_sec = 1; old[1].tv_usec = 0
            assertEquals(0, utimes("$dir/rec-stale.part", old))
            assertEquals(0, utimes("$dir/user.part", old))
        }
        assertEquals(0, symlink("$dir/user.part", "$dir/rec-link.part"))
        streamToFileAtomically("$dir/rec-live.part", 1, { "$dir/live" }) { sink ->
            sink(byteArrayOf(1))
            assertEquals(listOf("rec-stale.part"), sweepStaleDownloadParts(dir, 2000))
            assertTrue(exists("$dir/rec-fresh.part"))
            assertTrue(sweepStaleDownloadParts(dir, Long.MAX_VALUE).none { it == "rec-live.part" })
            assertTrue(exists("$dir/rec-live.part"))
        }
        assertTrue(exists("$dir/user.part"))
        assertTrue(exists("$dir/rec-link.part"))
        assertTrue(sweepStaleDownloadParts("$dir/missing", Long.MAX_VALUE).isEmpty())
    }
}
