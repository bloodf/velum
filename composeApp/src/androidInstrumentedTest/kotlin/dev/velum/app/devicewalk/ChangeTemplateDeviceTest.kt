package dev.velum.app.devicewalk

import android.content.Context
import android.os.SystemClock
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.velum.app.MainActivity
import dev.velum.app.VelumApplication
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #103 on a real Android runtime with the device-walk fake engines (no models, no network):
 * import the bundled WAV, process it, change the template (summary-only, from the stored
 * transcript) and check the new version, the retained previous version, and that both survive
 * an activity recreation. Requires runner arg `velumDeviceWalk=true`.
 */
@RunWith(AndroidJUnit4::class)
class ChangeTemplateDeviceTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private lateinit var appContext: Context

    @Before
    fun setUp() {
        assertTrue(DeviceWalkMode.isEnabled(), "DeviceWalkMode must be enabled via runner arg velumDeviceWalk=true")
        appContext = InstrumentationRegistry.getInstrumentation().targetContext
    }

    @Test
    fun changeTemplate_addsVersion_keepsPreviousVersion_andSurvivesRecreation() {
        val container = runBlocking { (appContext.applicationContext as VelumApplication).awaitContainer() }
        val wavBytes = InstrumentationRegistry.getInstrumentation().context.assets
            .open("device_walk_tone.wav").readBytes()
        val wav = File(File(appContext.filesDir, "recordings").also { it.mkdirs() }, "change-template-103.wav")
        wav.writeBytes(wavBytes)
        val id = runBlocking {
            container.createLocalRecording(
                title = "Change template ${SystemClock.uptimeMillis()}",
                source = "import",
                localPath = wav.absolutePath,
                status = "queued",
            )
        }

        runBlocking {
            container.recordingProcessor
                .process(recordingId = id, templateId = "velum.meeting", retranscribe = true, apiModelOverride = null)
                .collect { }
        }
        val first = runBlocking { container.repository.getRecording(id)!! }
        assertEquals("ready", first.status)
        val firstSummary = first.summary_markdown.orEmpty()
        assertTrue(firstSummary.isNotBlank())
        val transcript = first.transcript.orEmpty()
        assertTrue(transcript.isNotBlank(), "import must have produced a transcript to reuse")

        // Change template: summary-only run, as the app's Change template action enqueues it.
        runBlocking {
            container.recordingProcessor
                .process(recordingId = id, templateId = "velum.sales", retranscribe = false, apiModelOverride = null)
                .collect { }
        }

        verifyVersions(container, id, firstSummary, transcript)

        composeRule.runOnUiThread { composeRule.activity.recreate() }
        composeRule.waitForIdle()

        verifyVersions(container, id, firstSummary, transcript)
    }

    private fun verifyVersions(
        container: dev.velum.app.di.AppContainer,
        id: String,
        firstSummary: String,
        transcript: String,
    ) = runBlocking {
        val rec = container.repository.getRecording(id)!!
        assertEquals("ready", rec.status)
        assertEquals(transcript, rec.transcript, "transcript is reused, not regenerated")
        val versions = container.repository.getSummaryVersions(id)
        assertEquals(2, versions.size, "previous version retained next to the new one")
        val previous = versions.single { it.template_id == "velum.meeting" }
        val current = versions.single { it.template_id == "velum.sales" }
        assertEquals(firstSummary, previous.markdown)
        assertEquals(0L, previous.is_active)
        assertEquals(1L, current.is_active)
        assertEquals(current.markdown, rec.summary_markdown)
        assertNotEquals(previous.markdown, current.markdown)
        assertTrue(previous.template_name.isNotBlank() && current.template_name.isNotBlank())
    }
}
