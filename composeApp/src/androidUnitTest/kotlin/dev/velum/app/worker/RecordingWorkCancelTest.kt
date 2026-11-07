package dev.velum.app.worker

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import dev.velum.ai.pipeline.createWorkManagerRecordingJobScheduler
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #328: the wipe drops processing work that is queued or waiting to retry. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class RecordingWorkCancelTest {
    @Test fun cancel_all_cancels_queued_recording_work() = kotlinx.coroutines.runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val scheduler = createWorkManagerRecordingJobScheduler(context)
        scheduler.enqueue("rec-1", null, true, null)
        scheduler.enqueue("rec-2", null, true, null)
        val manager = WorkManager.getInstance(context)
        val states = { id: String -> manager.getWorkInfosByTag("recording:$id").get().map { it.state } }
        assertEquals(listOf(WorkInfo.State.ENQUEUED), states("rec-1"))

        scheduler.cancelAll()

        assertTrue(states("rec-1").all { it == WorkInfo.State.CANCELLED }, "work stayed queued: ${states("rec-1")}")
        assertTrue(states("rec-2").all { it == WorkInfo.State.CANCELLED }, "work stayed queued: ${states("rec-2")}")
    }

    @Test fun cancel_drops_only_that_recordings_work() = kotlinx.coroutines.runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val scheduler = createWorkManagerRecordingJobScheduler(context)
        scheduler.enqueue("rec-1", null, true, null)
        scheduler.enqueue("rec-2", null, true, null)
        val manager = WorkManager.getInstance(context)
        val states = { id: String -> manager.getWorkInfosByTag("recording:$id").get().map { it.state } }

        scheduler.cancel("rec-1")

        val cancelled = states("rec-1")
        assertTrue(cancelled.isNotEmpty(), "no work found for rec-1")
        assertTrue(cancelled.all { it == WorkInfo.State.CANCELLED }, "work stayed queued: $cancelled")
        assertEquals(listOf(WorkInfo.State.ENQUEUED), states("rec-2"))
    }
}
