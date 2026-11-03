package dev.velum.core.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking

/**
 * #208: FTS writes on the one shared in-memory JDBC connection must not collide with each other or
 * with other transactions ("cannot start a transaction within a transaction").
 */
class FtsConcurrentWritesTest {
    @Test
    fun concurrentIndexedWrites_neverFailAndLeaveIndexConsistent() = runBlocking {
        val handle = createDatabase(DriverFactory())
        val repo = VelumRepository(handle)
        try {
            val ids = (0 until 8).map { "rec-c-$it" }
            ids.forEach { id ->
                repo.insertRecording(
                    id = id, sessionId = null, folderId = null, title = "Synthetic", source = "phone",
                    deviceSerial = null, startedAt = 1, durationSeconds = 1, sizeBytes = 0,
                    localPath = null, transcript = "seed", summaryMarkdown = null, status = "ready",
                )
            }
            val failures = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
            (0 until 16).map { w ->
                async(Dispatchers.Default) {
                    repeat(150) { i ->
                        val id = ids[(w + i) % ids.size]
                        try {
                            when (i % 3) {
                                0 -> repo.updateSummary(id, "summary-$w-$i")
                                1 -> repo.updateTranscript(id, "transcript-$w-$i")
                                else -> repo.upsertTemplate("tpl-c-$w", "Name $w-$i", "meeting", "", "prompt")
                            }
                        } catch (t: Throwable) {
                            failures += t
                        }
                    }
                }
            }.awaitAll()
            assertTrue(failures.isEmpty(), "${failures.size} failed, first: ${failures.firstOrNull()?.stackTraceToString()}")
            // Exactly one index row per recording and per template after all the replaces.
            ids.forEach { id ->
                assertEquals(1, repo.searchRecordingIdsFts("seed OR transcript* OR summary*").count { it == id }, id)
            }
            assertEquals(16, (0 until 16).count { repo.searchTemplateIdsFts("name*").contains("tpl-c-$it") })
        } finally {
            handle.driver.close()
        }
    }

    /** Every repository call must stay on the serial dispatcher; a stray Dispatchers.Default call would bring the races back. */
    @Test
    fun repositoryNeverSwitchesToAParallelDispatcher() {
        val source = java.io.File("src/commonMain/kotlin/dev/velum/core/data/VelumRepository.kt").readText()
        assertTrue(source.length > 1000, "VelumRepository.kt not found from ${java.io.File("").absolutePath}")
        assertTrue(!source.contains("withContext(Dispatchers"), "use withContext(dbContext)")
    }
}
