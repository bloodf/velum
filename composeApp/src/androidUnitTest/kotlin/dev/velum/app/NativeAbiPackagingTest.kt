package dev.velum.app

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards that Needle JNI + libc++ are present for Galaxy / Pixel arm64 devices.
 * Packaging into the APK is also asserted in CI after assembleDebug.
 */
class NativeAbiPackagingTest {
    @Test
    fun arm64V8aNeedleLibsPresentOnDisk() {
        val roots = listOf(
            File("src/androidMain/jniLibs"),
            File("../ai/src/androidMain/jniLibs"),
            File("ai/src/androidMain/jniLibs"),
        )
        val jni = roots.firstOrNull { it.isDirectory }
            ?: error("jniLibs not found from cwd=${File(".").absolutePath}")
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
}
