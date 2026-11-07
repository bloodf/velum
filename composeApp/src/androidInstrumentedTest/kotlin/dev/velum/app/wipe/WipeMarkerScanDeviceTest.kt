package dev.velum.app.wipe

import android.os.Bundle
import app.cash.sqldelight.db.QueryResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.velum.ai.local.ModelStoreDir
import dev.velum.app.di.AppContainer
import dev.velum.app.ui.DownloadWipeResult
import dev.velum.core.data.DriverFactory
import dev.velum.core.security.InMemorySecureStore
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #423/#441 on a device with the real Android SQLite driver: text deleted by Wipe all data must not stay readable in
 * any database file, including the full-text index.
 *
 * Four synthetic tokens are written, one per place that is indexed (recording title, transcript and summary, and a
 * custom template's prompt, so `recording_fts` and `template_fts` are both covered). Each starts with a different
 * letter (no shared prefix) and ends with a digit (Porter stemming cannot change it). Rows hold the UPPERCASE form;
 * the full-text index stores terms lowercased, so a LOWERCASE hit can only come from the index. Both forms are
 * searched in every file of the database (main, -wal, -journal, -shm).
 * - Before the wipe both forms of every token must be found (positive controls for the rows and for the index).
 * - After `wipeAllData`, and again after the driver is closed, no form of any token may be found.
 * - `ftsAvailable` must be true (a framework-SQLite fallback with no index would pass vacuously), and the wipe result
 *   must not be Incomplete. The journal mode is reported.
 * The database is its own file (`wipe-marker-scan.db`, never velum.db), but wipeAllData also clears the app's other
 * wipeable state (recordings directory, settings): run it on a disposable install. Skipped unless `wipeMarker=true`.
 * It is expected to FAIL on a build without the VACUUM, the checkpoint and the full-text optimize steps (#441): the
 * lowercase index form survives a VACUUM alone; that is the control. The word before the title token shares no prefix
 * with any token, so the index cannot prefix-compress one token into another.
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
        val hex = java.lang.Long.toHexString(System.nanoTime()).uppercase()
        val tokens = linkedMapOf(
            "title" to "ZQTITLE${hex}4", "transcript" to "XQTRANS${hex}5",
            "summary" to "YQSUMM${hex}6", "template" to "VQTMPL${hex}7",
        )
        val container = AppContainer(
            InMemorySecureStore(), DriverFactory(ctx, DB),
            modelStoreRoot = ModelStoreDir(Files.createTempDirectory("velum-wipe-scan").toString()),
        )
        val report = mutableListOf<String>()
        var ok = false
        try {
            assertTrue("full-text search must be available, or the index is not under test", container.repository.ftsAvailable)
            report += "journal_mode=" + container.databaseHandle.driver.executeQuery(
                null, "PRAGMA journal_mode", { c -> c.next(); QueryResult.Value(c.getString(0)) }, 0,
            ).value
            runBlocking {
                // Otherwise the wipe cannot vouch for the API keys and reports Incomplete.
                runCatching { container.settingsRepository.load() }
                repeat(20) { i -> // unrelated rows, so the file has several pages
                    container.repository.insertRecording(
                        id = "rec-scan-$i", sessionId = null, folderId = null, title = "filler $i", source = "phone",
                        deviceSerial = null, startedAt = 1, durationSeconds = 1, sizeBytes = 0, localPath = null,
                        transcript = "filler transcript $i ".repeat(40), summaryMarkdown = "filler summary $i", status = "ready",
                    )
                }
                container.repository.insertRecording(
                    id = "rec-scan-marker", sessionId = null, folderId = null, title = "mark ${tokens["title"]}", source = "phone",
                    deviceSerial = null, startedAt = 1, durationSeconds = 1, sizeBytes = 0, localPath = null,
                    transcript = "Speaker 1: ${tokens["transcript"]}", summaryMarkdown = "## ${tokens["summary"]}", status = "ready",
                )
                container.repository.upsertTemplate(
                    id = "tpl-scan", name = "scan template", category = "scan", description = "scan",
                    systemPrompt = "prompt ${tokens["template"]}", builtin = false,
                )
            }
            val db = ctx.getDatabasePath(DB)
            val before = scan(db, tokens.values)
            report += "files before wipe: " + databaseFiles(db).joinToString { "${it.name}=${it.length()}B" }
            report += "before: $before"
            for ((where, t) in tokens) {
                assertTrue("control: $where row form ($t) must be in the files before the wipe: $before", (before[t] ?: 0) > 0)
                assertTrue("control: $where index form (lowercase) must be in the files before the wipe: $before", (before[t.lowercase()] ?: 0) > 0)
            }
            val result = runBlocking { container.wipeAllData() }
            report += "wipe result: $result"
            assertNotEquals("the wipe did not complete", DownloadWipeResult.Incomplete, result)
            val after = scan(db, tokens.values)
            report += "files after wipe: " + databaseFiles(db).joinToString { "${it.name}=${it.length()}B" }
            report += "after wipe: $after"
            assertTrue("text still readable after the wipe: ${after.filterValues { it > 0 }}", after.values.all { it == 0 })
            ok = true
        } finally {
            runCatching { container.databaseHandle.driver.close() }
            val closed = scan(ctx.getDatabasePath(DB), tokens.values)
            report += "after close: $closed"
            ctx.deleteDatabase(DB)
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("velum.wipeScan", report.joinToString(" | ")) })
            // Only checked when the test got here without failing, so it never replaces the original failure.
            if (ok) assertTrue("text readable after the driver was closed: ${closed.filterValues { it > 0 }}", closed.values.all { it == 0 })
        }
    }

    /** Occurrences of each token in both letter cases (UTF-8 bytes), summed over every database file. */
    private fun scan(db: File, tokens: Collection<String>): Map<String, Int> {
        val bytes = databaseFiles(db).map { it.readBytes() }
        val out = linkedMapOf<String, Int>()
        for (t in tokens) for (form in listOf(t, t.lowercase())) out[form] = bytes.sumOf { count(it, form.toByteArray()) }
        return out
    }

    private fun databaseFiles(db: File): List<File> =
        db.parentFile!!.listFiles { f -> f.name.startsWith(db.name) }.orEmpty().sortedBy { it.name }

    private fun count(h: ByteArray, n: ByteArray): Int {
        var c = 0
        var i = 0
        while (i <= h.size - n.size) {
            var j = 0
            while (j < n.size && h[i + j] == n[j]) j++
            if (j == n.size) { c++; i += n.size } else i++
        }
        return c
    }

    private companion object { const val DB = "wipe-marker-scan.db" }
}
