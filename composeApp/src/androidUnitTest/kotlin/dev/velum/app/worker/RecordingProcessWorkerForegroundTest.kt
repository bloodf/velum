package dev.velum.app.worker

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ForegroundInfo
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import androidx.work.impl.utils.futures.SettableFuture
import androidx.work.testing.TestListenableWorkerBuilder
import com.google.common.util.concurrent.ListenableFuture
import dev.velum.ai.pipeline.AiRuntimeHolder
import dev.velum.ai.pipeline.RecordingProcessWorker
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #330: a refused foreground start (background start restriction) must not fail the work permanently. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class RecordingProcessWorkerForegroundTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After fun reset() {
        AiRuntimeHolder.processor = null
        AiRuntimeHolder.repository = null
    }

    private fun worker(attempt: Int): RecordingProcessWorker =
        TestListenableWorkerBuilder<RecordingProcessWorker>(context)
            .setInputData(androidx.work.workDataOf(RecordingProcessWorker.KEY_RECORDING_ID to "rec-synthetic"))
            .setRunAttemptCount(attempt)
            .setForegroundUpdater { _, _: UUID, _: ForegroundInfo ->
                SettableFuture.create<Void>().also {
                    it.setException(IllegalStateException("startForegroundService not allowed (synthetic)"))
                }
            }
            .build()

    @Test fun a_refused_foreground_start_retries_instead_of_failing_the_work() {
        // Holder not installed yet: the retry path after setForeground, which a thrown setForeground never reached.
        val result = runBlocking { worker(attempt = 0).doWork() }
        assertEquals(ListenableWorker.Result.retry(), result)
    }

    @Test fun a_refused_foreground_start_still_ends_in_the_normal_terminal_failure() {
        val result = runBlocking { worker(attempt = RecordingProcessWorker.MAX_HOLDER_RETRIES).doWork() }
        assertTrue(result is ListenableWorker.Result.Failure, "got $result")
    }
}
