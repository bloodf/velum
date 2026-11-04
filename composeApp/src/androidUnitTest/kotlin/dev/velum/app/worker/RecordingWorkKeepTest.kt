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

    private fun enqueueOffMain(
        id: String,
        template: String?,
        override: ApiModelOverride? = null,
        retranscribe: Boolean = true,
    ): Boolean {
        // The params lookup is skipped on the main thread, which is not how the runner or MicFinalize call it.
        var started = false
        val t = Thread { started = RecordingProcessWorker.enqueue(context, id, template, retranscribe, override) }
        t.start(); t.join()
        return started
    }

    @Test fun sameTemplateAndModelKeepsTheQueuedWork() {
        assertEquals(true, enqueueOffMain("rec-keep", "velum.meeting"), "a first enqueue starts work")
        val first = infos("rec-keep").single().id
        assertEquals(false, enqueueOffMain("rec-keep", "velum.meeting"), "the second one reports it kept the work")
        val after = infos("rec-keep")
        assertEquals(1, after.size, "no second work item: $after")
        assertEquals(first, after.single().id, "the first work item survives")
    }

    @Test fun aBlankTemplateAndTheDefaultMeetingTemplateAreTheSameWork() {
        assertEquals(true, enqueueOffMain("rec-default", "velum.meeting"))
        val first = infos("rec-default").single().id
        // A blank template means the default meeting template, so this enqueue asks for the same work.
        assertEquals(false, enqueueOffMain("rec-default", "  "), "kept")
        assertEquals(listOf(first), infos("rec-default").map { it.id })
    }

    @Test fun aLegacyAliasIsADifferentTemplateAndReplacesTheQueuedWork() {
        enqueueOffMain("rec-alias", "velum.meeting")
        val first = infos("rec-alias").single().id
        assertEquals(true, enqueueOffMain("rec-alias", "tpl-meeting"), "an edited tpl-meeting is not the built-in")
        assertEquals(WorkInfo.State.ENQUEUED, infos("rec-alias").single { it.id != first }.state)
    }

    @Test fun aLaterIdenticalEnqueueAfterTheWorkFinishedStartsNewWork() {
        enqueueOffMain("rec-again", "velum.meeting")
        val first = infos("rec-again").single().id
        WorkManager.getInstance(context).cancelWorkById(first).result.get()
        assertEquals(true, enqueueOffMain("rec-again", "velum.meeting"), "finished work is not kept")
        val fresh = infos("rec-again").filter { it.id != first }
        assertEquals(1, fresh.size)
        assertEquals(WorkInfo.State.ENQUEUED, fresh.single().state)
    }

    @Test fun differentRetranscribeFlagReplacesTheQueuedWork() {
        enqueueOffMain("rec-flag", "velum.meeting", retranscribe = true)
        val first = infos("rec-flag").single().id
        assertEquals(true, enqueueOffMain("rec-flag", "velum.meeting", retranscribe = false))
        assertEquals(false, infos("rec-flag").any { it.id == first && it.state != WorkInfo.State.CANCELLED })
        assertEquals(WorkInfo.State.ENQUEUED, infos("rec-flag").single { it.id != first }.state)
    }

    @Test fun differentProfileReplacesTheQueuedWork() {
        enqueueOffMain("rec-profile", "velum.meeting", ApiModelOverride(profileId = "a"))
        val first = infos("rec-profile").single().id
        assertEquals(true, enqueueOffMain("rec-profile", "velum.meeting", ApiModelOverride(profileId = "b")))
        assertEquals(false, infos("rec-profile").any { it.id == first && it.state != WorkInfo.State.CANCELLED })
        assertEquals(WorkInfo.State.ENQUEUED, infos("rec-profile").single { it.id != first }.state)
    }

    @Test fun differentTemplateReplacesTheQueuedWork() {
        enqueueOffMain("rec-replace", "velum.meeting")
        val first = infos("rec-replace").single().id
        assertEquals(true, enqueueOffMain("rec-replace", "velum.summary"))
        val after = infos("rec-replace")
        assertEquals(false, after.any { it.id == first && it.state != WorkInfo.State.CANCELLED }, "the old work was replaced: $after")
        assertEquals(WorkInfo.State.ENQUEUED, after.single { it.id != first }.state, "the replacement is queued")
    }

    @Test fun differentModelReplacesTheQueuedWork() {
        enqueueOffMain("rec-model", "velum.meeting", ApiModelOverride(modelId = "a"))
        val first = infos("rec-model").single().id
        enqueueOffMain("rec-model", "velum.meeting", ApiModelOverride(modelId = "b"))
        assertEquals(false, infos("rec-model").any { it.id == first && it.state != WorkInfo.State.CANCELLED })
    }
}
