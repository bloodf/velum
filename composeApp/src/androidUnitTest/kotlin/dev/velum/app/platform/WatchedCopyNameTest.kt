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
}
