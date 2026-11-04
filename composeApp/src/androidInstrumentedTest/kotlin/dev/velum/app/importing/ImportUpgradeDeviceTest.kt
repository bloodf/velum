package dev.velum.app.importing

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.velum.app.VelumApplication
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #318/#420 upgrade check on a device, in two phases with an app upgrade between them (`adb install -r` of the new
 * build over the old one keeps the data):
 * - `seed` (run on the OLD build): imports a synthetic WAV from a file under cacheDir/imports through the real
 *   AudioImportPipeline and AudioNormalizer, and writes the recording id and the path the row points at to
 *   files/upgrade-seed.txt.
 * - `verify` (run on the NEW build after the upgrade; the app's startup migration has run by the time the container
 *   is ready): the row must point into files/recordings, that file must exist with the same length, and the cache
 *   copy must be gone.
 * Skipped unless `upgradePhase` is `seed` or `verify`. Synthetic audio only.
 *
 * Run: adb -s emulator-5554 shell am instrument -w -e class dev.velum.app.importing.ImportUpgradeDeviceTest \
 *   -e upgradePhase seed -e velumDeviceWalk false dev.velum.app.test/dev.velum.app.devicewalk.DeviceWalkTestRunner
 */
@RunWith(AndroidJUnit4::class)
class ImportUpgradeDeviceTest {
    private val args = InstrumentationRegistry.getArguments()
    private val phase get() = args.getString("upgradePhase").orEmpty()
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val seedFile get() = File(ctx.filesDir, "upgrade-seed.txt")

    @Test fun seed_imports_into_the_cache_on_the_old_build() {
        assumeTrue("phase is '$phase'", phase == "seed")
        val container = runBlocking { (ctx.applicationContext as VelumApplication).awaitContainer() }
        val dir = File(ctx.cacheDir, "imports").apply { mkdirs() }
        val src = File(dir, "upgrade-seed-${System.nanoTime()}.wav")
        src.writeBytes(wav(3))
        val result = runBlocking { AudioImportPipeline(container).importFile(src.absolutePath) }
        assertTrue("import result $result", result is ImportResult.Created)
        val created = result as ImportResult.Created
        val row = runBlocking { container.repository.getRecording(created.recordingId)!! }
        seedFile.writeText("${created.recordingId}\n${row.local_path}\n${File(row.local_path!!).length()}\n")
        InstrumentationRegistry.getInstrumentation().sendStatus(
            0, Bundle().apply { putString("velum.upgradeSeed", "rowPath=${row.local_path} bytes=${File(row.local_path!!).length()} inCache=${row.local_path!!.startsWith(ctx.cacheDir.path)}") },
        )
    }

    @Test fun verify_the_row_moved_out_of_the_cache_after_the_upgrade() {
        assumeTrue("phase is '$phase'", phase == "verify")
        val (id, oldPath, oldBytes) = seedFile.readLines().let { Triple(it[0], it[1], it[2].toLong()) }
        val container = runBlocking { (ctx.applicationContext as VelumApplication).awaitContainer() }
        val recordings = File(ctx.filesDir, "recordings").canonicalPath
        // The migration runs at startup, before the container is published; allow a moment for the row write.
        var row = runBlocking { container.repository.getRecording(id) }
        val end = System.currentTimeMillis() + 30_000
        while (row?.local_path?.let { File(it).canonicalPath.startsWith(recordings) } != true && System.currentTimeMillis() < end) {
            Thread.sleep(500); row = runBlocking { container.repository.getRecording(id) }
        }
        val path = row!!.local_path!!
        val moved = File(path)
        InstrumentationRegistry.getInstrumentation().sendStatus(
            0, Bundle().apply {
                putString(
                    "velum.upgradeVerify",
                    "before=$oldPath after=$path exists=${moved.isFile} bytes=${moved.length()} (was $oldBytes) oldGone=${!File(oldPath).exists()} inCacheNow=${File(path).canonicalPath.startsWith(ctx.cacheDir.canonicalPath)}",
                )
            },
        )
        assertTrue("row still outside files/recordings: $path", moved.canonicalPath.startsWith(recordings))
        assertTrue("moved file missing", moved.isFile)
        assertEquals("size changed by the move", oldBytes, moved.length())
        assertFalse("old cache file still there: $oldPath", File(oldPath).exists())
        seedFile.delete()
    }

    private fun wav(seconds: Int): ByteArray {
        val rate = 16_000
        val data = ByteBuffer.allocate(seconds * rate * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until seconds * rate) data.putShort((sin(2 * PI * 440 * i / rate) * 8000).toInt().toShort())
        fun le32(v: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
        fun le16(v: Int) = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array()
        val fmt = "fmt ".toByteArray() + le32(16) + le16(1) + le16(1) + le32(rate) + le32(rate * 2) + le16(2) + le16(16)
        val body = "WAVE".toByteArray() + fmt + "data".toByteArray() + le32(data.capacity()) + data.array()
        return "RIFF".toByteArray() + le32(body.size) + body
    }
}
