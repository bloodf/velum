package dev.velum.app.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class WatchedCopyNameTest {
    @Test
    fun sameNameAndMtimeInTwoDocumentsGetDifferentCopies_andTheSameDocumentTheSameCopy() {
        val a = watchedCopyName("content://tree/A/document/memo.m4a", 1_700_000_000_000, "memo.m4a")
        val b = watchedCopyName("content://tree/B/document/memo.m4a", 1_700_000_000_000, "memo.m4a")
        assertNotEquals(a, b)
        assertEquals(a, watchedCopyName("content://tree/A/document/memo.m4a", 1_700_000_000_000, "memo.m4a"))
        assertTrue(a.endsWith("_memo.m4a") && a.startsWith("1700000000000_"))
    }

    @Test
    fun aLongOrOddNameStaysUnderTheFileNameLimitAndKeepsItsExtension() {
        val long = "a".repeat(300) + " (final)\u00e9.m4a"
        val copy = watchedCopyName("content://tree/A/document/x", 1_700_000_000_000, long)
        assertTrue(copy.toByteArray().size < 255, "copy name is ${copy.toByteArray().size} bytes")
        assertTrue(copy.endsWith(".m4a") && !copy.contains(' ') && !copy.contains('('))
    }

    @Test
    fun theLegacyCopyIsRemovedOnceTheNewOneExists_andTheNewOneIsNeverTouched() {
        val root = java.nio.file.Files.createTempDirectory("velum-319-watched").toFile()
        try {
            val legacy = java.io.File(root, "1700000000000_memo.m4a").apply { writeText("old copy") }
            val current = java.io.File(root, watchedCopyName("content://tree/A/document/memo", 1_700_000_000_000, "memo.m4a")).apply { writeText("new copy") }
            assertTrue(removeLegacyWatchedCopy(root, 1_700_000_000_000, "memo.m4a", current))
            assertTrue(!legacy.exists() && current.isFile)
            assertEquals(false, removeLegacyWatchedCopy(root, 1_700_000_000_000, "memo.m4a", current), "nothing left to remove")
            // The legacy and current names are never the same file, but a caller passing it must not lose it.
            val same = java.io.File(root, "5_x.m4a").apply { writeText("keep") }
            assertEquals(false, removeLegacyWatchedCopy(root, 5, "x.m4a", same))
            assertTrue(same.isFile)
        } finally {
            root.deleteRecursively()
        }
    }
}
