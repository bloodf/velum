package dev.velum.app

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards that Needle JNI + libc++ are present for Galaxy / Pixel arm64 devices,
 * and that arm64 libvelum_needle.so is 16 KB ELF-aligned (Play / Android 15+).
 * Packaging into the APK is also asserted in CI after assembleDebug.
 */
class NativeAbiPackagingTest {
    @Test
    fun arm64V8aNeedleLibsPresentOnDisk() {
        val jni = resolveJniLibs()
        val arm64 = File(jni, "arm64-v8a")
        assertTrue("missing arm64-v8a dir at $arm64", arm64.isDirectory)
        assertTrue(
            "missing libvelum_needle.so",
            File(arm64, "libvelum_needle.so").isFile,
        )
        assertTrue(
            "missing libc++_shared.so",
            File(arm64, "libc++_shared.so").isFile,
        )
        assertTrue(
            "missing armeabi-v7a/libvelum_needle.so",
            File(jni, "armeabi-v7a/libvelum_needle.so").isFile,
        )
    }

    @Test
    fun arm64NeedleHas16KbPtLoadAlign() {
        val needle = File(resolveJniLibs(), "arm64-v8a/libvelum_needle.so")
        assertTrue("missing $needle", needle.isFile)
        val aligns = elfPtLoadAligns(needle.readBytes())
        assertTrue("no PT_LOAD in $needle", aligns.isNotEmpty())
        for (align in aligns) {
            assertTrue(
                "PT_LOAD Align 0x${align.toString(16)} < 0x4000 in $needle",
                align >= 0x4000L,
            )
        }
    }

    private fun resolveJniLibs(): File {
        val roots = listOf(
            File("src/androidMain/jniLibs"),
            File("../ai/src/androidMain/jniLibs"),
            File("ai/src/androidMain/jniLibs"),
        )
        return roots.firstOrNull { it.isDirectory }
            ?: error("jniLibs not found from cwd=${File(".").absolutePath}")
    }

    /** Minimal ELF64 little-endian PT_LOAD p_align reader (Android arm64 .so). */
    private fun elfPtLoadAligns(data: ByteArray): List<Long> {
        require(data.size >= 64 && data[0] == 0x7f.toByte() && data[1] == 'E'.code.toByte()) {
            "not an ELF file"
        }
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val eiClass = data[4].toInt() and 0xff
        require(eiClass == 2) { "expected ELF64" }
        val ePhOff = buf.getLong(32)
        val ePhEntSize = buf.getShort(54).toInt() and 0xffff
        val ePhNum = buf.getShort(56).toInt() and 0xffff
        val aligns = ArrayList<Long>()
        for (i in 0 until ePhNum) {
            val off = ePhOff + i.toLong() * ePhEntSize
            val pType = buf.getInt(off.toInt())
            if (pType == 1) { // PT_LOAD
                val pAlign = buf.getLong(off.toInt() + 48)
                aligns += pAlign
            }
        }
        return aligns
    }
}
