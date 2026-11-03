package dev.velum.app.platform

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException

class CopyToCacheTest {
    private fun target() = File.createTempFile("velum-copy", ".wav").also { it.delete() }

    private class Failing(private val after: Int, private val error: Exception) : InputStream() {
        private var n = 0
        override fun read(): Int = if (n++ < after) 1 else throw error
    }

    @Test
    fun goodStream_isCopied() {
        val out = target()
        assertTrue(copyToCache({ ByteArrayInputStream(byteArrayOf(1, 2, 3)) }, out))
        assertEquals(3, out.length())
        out.delete()
    }

    @Test
    fun providerFailureMidCopy_returnsFalse_andLeavesNoPartialFile() {
        val out = target()
        assertFalse(copyToCache({ Failing(5, IOException("offline")) }, out))
        assertFalse(out.exists())
    }

    @Test
    fun openFailureAndRevokedGrant_returnFalse() {
        val out = target()
        assertFalse(copyToCache({ throw SecurityException("grant revoked") }, out))
        assertFalse(copyToCache({ null }, out))
        assertFalse(out.exists())
    }

    @Test
    fun emptyStream_isNotAnImport() {
        val out = target()
        assertFalse(copyToCache({ ByteArrayInputStream(ByteArray(0)) }, out))
        assertFalse(out.exists())
    }

    @Test
    fun cancellation_propagates_andCleansUp() {
        val out = target()
        assertFailsWith<CancellationException> { copyToCache({ Failing(3, CancellationException("cancelled")) }, out) }
        assertFalse(out.exists())
    }
}
