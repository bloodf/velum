package dev.velum.core.data

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * An observer (AutoFlow) reacts to a new row by writing to it. insertRecording used to publish
 * the row before its FTS index write finished, so both ran FTS BEGIN/COMMIT at once on the one
 * in-memory JDBC connection ("cannot start a transaction within a transaction", or a hang).
 */
class InsertRecordingObserverRaceTest {
    @Test
    fun observerWritingToNewRow_neverCollidesWithInsertIndexing() = runBlocking {
        val failures = mutableListOf<Throwable>()
        repeat(300) { i ->
            val handle = createDatabase(DriverFactory())
            val repo = VelumRepository(handle)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val id = "rec-race-$i"
                val reacted = scope.async {
                    repo.observeRecordings().first { rows -> rows.any { it.id == id } }
                    repo.updateSummary(id, "## result")
                }
                repo.insertRecording(
                    id = id, sessionId = null, folderId = null, title = "Synthetic", source = "phone",
                    deviceSerial = null, startedAt = 1, durationSeconds = 10, sizeBytes = 0,
                    localPath = null, transcript = null, summaryMarkdown = null, status = "imported",
                )
                withTimeout(10_000) { reacted.await() }
            } catch (t: Throwable) {
                failures += t
            } finally {
                scope.cancel()
                handle.driver.close()
            }
        }
        assertTrue(failures.isEmpty(), "${failures.size} of 300 failed, first: ${failures.firstOrNull()}")
    }
}
