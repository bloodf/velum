package dev.velum.core.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Seed version bumps must not INSERT OR REPLACE user-edited `flow-default`.
 */
class SeedFlowDefaultPreservedTest {
    @Test
    fun seedBump_preservesUserEditedFlowDefault() = runTest {
        val handle = createDatabase(DriverFactory())
        // VelumRepository.init runs maybeSeed once.
        VelumRepository(handle)
        val db = handle.database
        val q = db.velumQueries
        assertTrue(q.selectAutoflows().executeAsList().any { it.id == "flow-default" })
        q.updateAutoflow(
            name = "My custom Note Pro flow",
            enabled = 0,
            wake_phrase = "standup",
            duration_min_seconds = 60,
            duration_max_seconds = 900,
            source_filter = "note_pro",
            template_id = "tpl-custom",
            auto_label_voice = 0,
            language = "en",
            notify = 0,
            post_action = "webhook",
            model_id = "gpt-test",
            endpoint_profile_id = "profile-a",
            id = "flow-default",
        )
        // Force seed to run again (version bump).
        q.setMeta("demo_seed_version", "9")
        assertTrue(SeedData.maybeSeed(db))

        val flow = q.selectAutoflows().executeAsList().single { it.id == "flow-default" }
        assertEquals("My custom Note Pro flow", flow.name)
        assertEquals(0L, flow.enabled)
        assertEquals("standup", flow.wake_phrase)
        assertEquals("tpl-custom", flow.template_id)
        assertEquals("webhook", flow.post_action)
        assertEquals("gpt-test", flow.model_id)
        assertEquals("profile-a", flow.endpoint_profile_id)
    }
}
