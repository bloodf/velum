package dev.velum.app

import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

/** #119: startup cleanup completes before the container is published. No timing/delay. */
class StartupSequenceTest {
    @Test fun cleanup_finishes_before_publish() = runBlocking {
        val events = mutableListOf<String>()
        cleanupThenPublish("c", cleanup = { events += "cleanup:$it" }, publish = { events += "publish:$it" })
        assertEquals(listOf("cleanup:c", "publish:c"), events)
    }

    @Test fun failing_cleanup_is_reported_and_still_publishes() = runBlocking {
        val events = mutableListOf<String>()
        cleanupThenPublish(
            "c",
            cleanup = { throw IOException("synthetic") },
            publish = { events += "publish" },
            onCleanupFailure = { events += "failed:${it::class.simpleName}" },
        )
        assertEquals(listOf("failed:IOException", "publish"), events)
    }

    @Test fun cancellation_propagates_and_skips_publish() {
        var published = false
        assertFailsWith<CancellationException> {
            runBlocking { cleanupThenPublish("c", cleanup = { throw CancellationException("synthetic") }, publish = { published = true }) }
        }
        assertEquals(false, published)
    }

    /**
     * Mutation guard: VelumApplication must publish only inside cleanupThenPublish's publish block.
     * A source scan, not a behavioural test: onCreate builds the real AppContainer (Keystore-backed
     * AndroidSecureStore, WorkManager scheduler, real filesDir) with no injection seam, so Robolectric
     * cannot drive it deterministically. The ordering itself is covered behaviourally above.
     */
    @Test fun velum_application_publishes_only_after_cleanup() {
        val src = listOf(
            File("src/androidMain/kotlin/dev/velum/app/VelumApplication.kt"),
            File("composeApp/src/androidMain/kotlin/dev/velum/app/VelumApplication.kt"),
        ).first { it.isFile }.readText()
        val call = src.indexOf("cleanupThenPublish(")
        val cleanup = src.indexOf("cleanup = {", call)
        val sweep = src.indexOf("sweepOrphanRecordingAudio(", call)
        val publish = src.indexOf("publish = {", call)
        val publishEnd = src.indexOf("onCleanupFailure =", publish)
        assertTrue(call >= 0 && cleanup > call && sweep > cleanup && publish > sweep, "cleanup (sweep) must precede publish")
        val successPath = src.substring(0, src.indexOf("} catch (t: Throwable)"))
        for (marker in listOf("containerDeferred.complete(", "ready.set(true)")) {
            val first = successPath.indexOf(marker)
            assertTrue(first in (publish + 1) until publishEnd, "$marker must only appear inside the publish block")
            assertEquals(first, successPath.lastIndexOf(marker), "$marker must appear once on the success path")
        }
    }

    /** #197: the PCM spill sweep must run before a re-run job can exist, i.e. before AiRuntimeHolder.install. */
    @Test fun pcm_spill_sweep_runs_before_worker_gate_opens() {
        val src = listOf(
            File("src/androidMain/kotlin/dev/velum/app/VelumApplication.kt"),
            File("composeApp/src/androidMain/kotlin/dev/velum/app/VelumApplication.kt"),
        ).first { it.isFile }.readText()
        val sweep = src.indexOf("sweepOrphanPcmSpill(")
        val install = src.indexOf("AiRuntimeHolder.install(")
        assertTrue(sweep in 0 until install, "sweepOrphanPcmSpill must precede AiRuntimeHolder.install")
    }
}
