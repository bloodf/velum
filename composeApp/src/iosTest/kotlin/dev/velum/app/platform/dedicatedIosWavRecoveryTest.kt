@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.velum.app.platform

import kotlinx.cinterop.*
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.posix.*
import kotlin.test.*

class DedicatedIosWavRecoveryTest {
    private fun fixture(test: (String) -> Unit) {
        val path = NSTemporaryDirectory() + NSUUID().UUIDString + ".wav"
        try { test(path) } finally { unlink(path); unlink("$path.partial") }
    }
    private fun bytes(path: String): ByteArray {
        val fd = open(path, O_RDONLY)
        check(fd >= 0)
        try {
            val size = lseek(fd, 0, SEEK_END).toInt()
            lseek(fd, 0, SEEK_SET)
            return ByteArray(size).also { b -> b.usePinned { assertEquals(size.toLong(), read(fd, it.addressOf(0), size.convert())) } }
        } finally { close(fd) }
    }
    private fun replace(path: String, b: ByteArray) {
        val fd = open(path, O_WRONLY or O_TRUNC)
        check(fd >= 0)
        try { b.usePinned { assertEquals(b.size.toLong(), write(fd, it.addressOf(0), b.size.convert())) } } finally { close(fd) }
    }
    private fun record(path: String, pcm: ByteArray = byteArrayOf(1, 2, 3, 4)) {
        WavWriter(path).apply { writePcm(pcm); finish(); finish() }
    }
    @Test fun writesHeaderAndPcmAndRefusesOverwrite() = fixture { path ->
        record(path)
        val b = bytes(path)
        assertEquals(48, b.size)
        assertEquals("RIFF", b.copyOfRange(0, 4).decodeToString())
        assertEquals("WAVE", b.copyOfRange(8, 12).decodeToString())
        assertEquals(40, b[4].toInt())
        assertEquals(4, b[40].toInt())
        assertContentEquals(byteArrayOf(1, 2, 3, 4), b.copyOfRange(44, 48))
        assertFails { WavWriter(path) }
        assertContentEquals(b, bytes(path))
    }
    @Test fun stalePartialPublishesAndRetainsOriginal() = fixture { path ->
        val partial = "$path.partial"
        record(partial)
        val stale = bytes(partial).also { for (i in 4..7) it[i] = 0; for (i in 40..43) it[i] = 0 }
        replace(partial, stale)
        val result = assertIs<WavRecoveryResult.Recovered>(WavWriter.recoverWithReport(partial))
        assertTrue(result.partialPreserved)
        assertEquals(path, result.finish.absolutePath)
        assertContentEquals(stale, bytes(partial))
        assertEquals(4, bytes(path)[40].toInt())
        assertTrue(assertIs<WavRecoveryResult.Recovered>(WavWriter.recoverWithReport(partial)).reusedFinal)
    }
    @Test fun staleFinalRepairsOnlySizes() = fixture { path ->
        record(path)
        val expected = bytes(path)
        replace(path, expected.copyOf().also { it[4] = 0; it[40] = 0 })
        assertIs<WavRecoveryResult.Recovered>(WavWriter.recoverWithReport(path))
        assertContentEquals(expected, bytes(path))
    }
    @Test fun malformedAndTruncatedOriginalsStayUntouched() = fixture { path ->
        record(path)
        val good = bytes(path)
        for (bad in listOf(good.copyOf().also { it[22] = 2 }, good.copyOf(20), good.copyOf(47), good.copyOf().also { it[0] = 0 })) {
            replace(path, bad)
            assertIs<WavRecoveryResult.Invalid>(WavWriter.recoverWithReport(path))
            assertContentEquals(bad, bytes(path))
        }
    }
    @Test fun conflictingDestinationAndPartialStayUntouched() = fixture { path ->
        record(path)
        record("$path.partial", byteArrayOf(9, 8, 7, 6))
        val final = bytes(path); val partial = bytes("$path.partial")
        assertIs<WavRecoveryResult.Conflict>(WavWriter.recoverWithReport("$path.partial"))
        assertContentEquals(final, bytes(path)); assertContentEquals(partial, bytes("$path.partial"))
    }
    @Test fun missingPartialCanReuseLegacyFinal() = fixture { path ->
        assertIs<WavRecoveryResult.Missing>(WavWriter.recoverWithReport(path))
        record(path)
        assertTrue(assertIs<WavRecoveryResult.Recovered>(WavWriter.recoverWithReport("$path.partial")).reusedFinal)
    }
}
