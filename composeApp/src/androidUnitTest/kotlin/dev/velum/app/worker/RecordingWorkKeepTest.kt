package dev.velum.app.worker

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import dev.velum.ai.pipeline.RecordingProcessWorker
import dev.velum.core.settings.ApiModelOverride
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #324: an enqueue for the same template and model keeps the queued work; a different one replaces it. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class RecordingWorkKeepTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before fun init() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(Executors.newSingleThreadExecutor()).build(),
        )
    }

    private fun infos(id: String): List<WorkInfo> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(RecordingProcessWorker.UNIQUE_PREFIX + id).get()

    private fun enqueueOffMain(id: String, template: String?, override: ApiModelOverride? = null) {
        // The params lookup is skipped on the main thread, which is not how the runner or MicFinalize call it.
        val t = Thread { RecordingProcessWorker.enqueue(context, id, template, true, override) }
        t.start(); t.join()
    }

    @Test fun sameTemplateAndModelKeepsTheQueuedWork() {
        enqueueOffMain("rec-keep", "velum.meeting")
        val first = infos("rec-keep").single().id
        enqueueOffMain("rec-keep", "velum.meeting")
        val after = infos("rec-keep")
        assertEquals(1, after.size, "no second work item: $after")
        assertEquals(first, after.single().id, "the first work item survives")
    }

    @Test fun differentTemplateReplacesTheQueuedWork() {
        enqueueOffMain("rec-replace", "velum.meeting")
        val first = infos("rec-replace").single().id
        enqueueOffMain("rec-replace", "velum.summary")
        val after = infos("rec-replace")
        assertEquals(false, after.any { it.id == first && it.state != WorkInfo.State.CANCELLED }, "the old work was replaced: $after")
    }

    @Test fun differentModelReplacesTheQueuedWork() {
        enqueueOffMain("rec-model", "velum.meeting", ApiModelOverride(modelId = "a"))
        val first = infos("rec-model").single().id
        enqueueOffMain("rec-model", "velum.meeting", ApiModelOverride(modelId = "b"))
        assertEquals(false, infos("rec-model").any { it.id == first && it.state != WorkInfo.State.CANCELLED })
    }
}
