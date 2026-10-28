package dev.velum.core.autoflow

import dev.velum.core.data.DriverFactory
import dev.velum.core.data.VelumRepository
import dev.velum.core.data.createDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class AutoFlowResolveTest {
    @Test
    fun firstMatch_prefersEnabledNoteProFlow_withTemplateAndModel() = runTest {
        val repo = VelumRepository(createDatabase(DriverFactory()))
        repo.setAutoflowEnabled("flow-default", false)
        repo.upsertAutoflow(
            id = "flow-note",
            name = "Note Pro notes",
            enabled = true,
            wakePhrase = null,
            durationMinSeconds = 0,
            durationMaxSeconds = 3600,
            sourceFilter = "note_pro",
            templateId = "velum.meeting",
            autoLabelVoice = true,
            language = "auto",
            notify = true,
            postAction = null,
            modelId = "gpt-4o-mini",
            endpointProfileId = "work",
        )
        val match = repo.findMatchingAutoflow(
            source = "note_pro",
            durationSeconds = 0,
            title = "Recording 1",
        )
        assertNotNull(match)
        assertEquals("velum.meeting", match.template_id)
        val override = AutoFlowResolve.modelOverride(match)
        assertNotNull(override)
        assertEquals("gpt-4o-mini", override.modelId)
        assertEquals("work", override.profileId)
    }

    @Test
    fun firstMatch_ignoresDisabledAndNonMatchingSource() = runTest {
        val repo = VelumRepository(createDatabase(DriverFactory()))
        repo.setAutoflowEnabled("flow-default", false)
        repo.upsertAutoflow(
            id = "flow-phone",
            name = "Phone only",
            enabled = true,
            wakePhrase = null,
            durationMinSeconds = 0,
            durationMaxSeconds = 3600,
            sourceFilter = "phone",
            templateId = "velum.meeting",
            autoLabelVoice = false,
            language = "auto",
            notify = true,
            postAction = null,
        )
        assertNull(
            repo.findMatchingAutoflow(source = "note_pro", durationSeconds = 10),
        )
    }
}
