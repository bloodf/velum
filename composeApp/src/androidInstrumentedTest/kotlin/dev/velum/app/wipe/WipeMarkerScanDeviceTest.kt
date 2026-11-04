package dev.velum.app.wipe

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.velum.ai.local.ModelStoreDir
import dev.velum.app.di.AppContainer
import dev.velum.core.data.DriverFactory
import dev.velum.core.security.InMemorySecureStore
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #423/#441 on a device with the real Android SQLite driver: text deleted by Wipe all data must not stay readable in
 * any database file. A synthetic marker is written into a recording's title, transcript and summary (so also the FTS
 * tables) in a database of its own (`wipe-marker-scan.db`, never velum.db); the control scan must find it in the files;
 * after `wipeAllData` every file of that database (main, -wal, -journal, -shm) is scanned again and must not contain it.
 * Note that wipeAllData also clears the app's other wipeable state (recordings directory, settings), so run it on a
 * disposable install.
 * Skipped unless `wipeMarker` is `true`.
 *
 * Run: adb -s emulator-5554 shell am instrument -w -e class dev.velum.app.wipe.WipeMarkerScanDeviceTest \
 *   -e wipeMarker true -e velumDeviceWalk false dev.velum.app.test/dev.velum.app.devicewalk.DeviceWalkTestRunner
 */
@RunWith(AndroidJUnit4::class)
class WipeMarkerScanDeviceTest {
    @Test fun deleted_text_is_not_left_in_the_database_files_after_wipe() {
        assumeTrue("wipeMarker not given", InstrumentationRegistry.getArguments().getString("wipeMarker") == "true")
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        ctx.deleteDatabase(DB)
        val marker = "ZQWIPEMARK" + java.lang.Long.toHexString(System.nanoTime())
        val container = AppContainer(
            InMemorySecureStore(), DriverFactory(ctx, DB),
            modelStoreRoot = ModelStoreDir(Files.createTempDirectory("velum-wipe-scan").toString()),
        )
        val report = mutableListOf<String>()
        try {
            runBlocking {
                repeat(20) { i -> // some unrelated rows, so the file has several pages
                    container.repository.insertRecording(
                        id = "rec-scan-$i", sessionId = null, folderId = null, title = "filler $i", source = "phone",
                        deviceSerial = null, startedAt = 1, durationSeconds = 1, sizeBytes = 0, localPath = null,
                        transcript = "filler transcript $i ".repeat(40), summaryMarkdown = "filler summary $i", status = "ready",
                    )
                }
                container.repository.insertRecording(
                    id = "rec-scan-marker", sessionId = null, folderId = null, title = "title $marker", source = "phone",
                    deviceSerial = null, startedAt = 1, durationSeconds = 1, sizeBytes = 0, localPath = null,
                    transcript = "Speaker 1: transcript $marker", summaryMarkdown = "## Summary $marker", status = "ready",
                )
            }
            val before = scan(ctx.getDatabasePath(DB), marker)
            report += "before: $before"
            assertTrue("control: the marker must be in the files before the wipe ($before)", before.values.any { it > 0 })
            val result = runBlocking { container.wipeAllData() }
            report += "wipe result: $result"
            val after = scan(ctx.getDatabasePath(DB), marker)
            report += "after: $after"
            assertTrue("marker still readable in $after", after.values.all { it == 0 })
        } finally {
            runCatching { container.databaseHandle.driver.close() }
            val closed = scan(ctx.getDatabasePath(DB), marker)
            report += "after close: $closed"
            ctx.deleteDatabase(DB)
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("velum.wipeScan", report.joinToString(" | ")) })
            assertTrue("marker readable after the driver was closed: $closed", closed.values.all { it == 0 })
        }
    }

    /** Occurrences of [marker] per database file (UTF-8 bytes), with file sizes. */
    private fun scan(db: File, marker: String): Map<String, Int> {
        val needle = marker.toByteArray()
        return db.parentFile!!.listFiles { f -> f.name.startsWith(db.name) }.orEmpty().sortedBy { it.name }.associate { f ->
            val bytes = f.readBytes()
            var count = 0
            var i = 0
            while (true) {
                i = indexOf(bytes, needle, i)
                if (i < 0) break
                count++; i += needle.size
            }
            "${f.name}(${bytes.size}B)" to count
        }
    }

    private fun indexOf(h: ByteArray, n: ByteArray, from: Int): Int {
        outer@ for (i in from..h.size - n.size) {
            for (j in n.indices) if (h[i + j] != n[j]) continue@outer
            return i
        }
        return -1
    }

    private companion object { const val DB = "wipe-marker-scan.db" }
}
