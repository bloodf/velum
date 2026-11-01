package dev.velum.core.data

import dev.velum.core.templates.BuiltinTemplateCatalog
import dev.velum.core.templates.PlaudTemplateAliases
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * #99: installs that imported the pre-dedupe "All" catalog still hold builtin rows whose
 * body duplicated a survivor. Restart must drop them, keep user templates, and repoint
 * references so nothing dangles.
 */
class TemplateDuplicateReconcileTest {
    private val alias = PlaudTemplateAliases.RemovedDuplicates.entries.first()

    @Test
    fun restart_dropsRemovedBuiltinDuplicate_andRepointsAutoflow() = runBlocking {
        val (removed, survivor) = alias
        val handle = createDatabase(DriverFactory())
        val repo = VelumRepository(handle)
        val q = handle.database.velumQueries
        repo.upsertTemplate(removed, "Zzqremoved copy", "meeting", "", "same body", builtin = true)
        repo.upsertTemplate(survivor, "Survivor", "meeting", "", "same body", builtin = true)
        repo.upsertTemplate("tpl-user-1-0000000001", "Mine", "mine", "", "my prompt")
        val flow = q.selectAutoflows().executeAsList().single { it.id == "flow-default" }
        q.updateAutoflow(
            name = flow.name,
            enabled = flow.enabled,
            wake_phrase = flow.wake_phrase,
            duration_min_seconds = flow.duration_min_seconds,
            duration_max_seconds = flow.duration_max_seconds,
            source_filter = flow.source_filter,
            template_id = removed,
            auto_label_voice = flow.auto_label_voice,
            language = flow.language,
            notify = flow.notify,
            post_action = flow.post_action,
            model_id = flow.model_id,
            endpoint_profile_id = flow.endpoint_profile_id,
            id = flow.id,
        )

        val restarted = VelumRepository(handle)

        assertNull(restarted.getTemplate(removed), "removed duplicate must be gone")
        assertNotNull(restarted.getTemplate(survivor))
        assertNotNull(restarted.getTemplate("tpl-user-1-0000000001"), "user template kept")
        assertEquals(
            survivor,
            q.selectAutoflows().executeAsList().single { it.id == "flow-default" }.template_id,
        )
        if (restarted.ftsAvailable) {
            assertTrue(restarted.searchTemplateIdsFts("zzqremoved").isEmpty(), "FTS row dropped")
        }
    }

    @Test
    fun customTemplateWithRemovedId_isNeverDeleted() = runBlocking {
        val (removed, _) = alias
        val handle = createDatabase(DriverFactory())
        VelumRepository(handle).upsertTemplate(removed, "Custom", "mine", "", "user prompt")

        val restarted = VelumRepository(handle)

        assertEquals("Custom", restarted.getTemplate(removed)?.name)
    }

    @Test
    fun resolveId_mapsRemovedDuplicateToSurvivor() {
        PlaudTemplateAliases.RemovedDuplicates.forEach { (removed, survivor) ->
            assertEquals(survivor, BuiltinTemplateCatalog.resolveId(removed))
        }
        assertEquals("tpl-user-x", BuiltinTemplateCatalog.resolveId("tpl-user-x"))
    }
}
