package dev.velum.core.data

import dev.velum.core.templates.TemplateBodyDedupeRemap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Existing installs that imported pre-dedupe builtins must remap references and
 * drop duplicate builtin rows without touching user-created templates (#99).
 */
class BodyDedupeReconcileTest {
    @Test
    fun reconcile_remaps_refs_deletes_builtin_dupes_keeps_custom() = runTest {
        val handle = createDatabase(DriverFactory())
        val db = handle.database
        val q = db.velumQueries
        // Clear the one-shot flag that VelumRepository.init may have set.
        q.setMeta(TemplateBodyDedupeRemap.META_KEY, "0")

        val (oldId, survivorId) = TemplateBodyDedupeRemap.OldToSurvivor.entries.first()
        val now = 1_700_000_000L
        q.insertTemplate(
            id = oldId,
            name = "Dropped dup",
            category = "meeting",
            description = "d",
            author = "Velum",
            system_prompt = "Instructions:\nbody",
            uses = 0,
            builtin = 1,
            featured = 0,
            tags = "summary",
            lang = "en",
            preferred_model = null,
            preferred_endpoint_profile_id = null,
            created_at = now,
        )
        q.insertTemplate(
            id = survivorId,
            name = "Survivor",
            category = "meeting",
            description = "d",
            author = "Velum",
            system_prompt = "Instructions:\nbody",
            uses = 1,
            builtin = 1,
            featured = 0,
            tags = "summary",
            lang = "en",
            preferred_model = null,
            preferred_endpoint_profile_id = null,
            created_at = now,
        )
        q.insertTemplate(
            id = "user.custom.$oldId",
            name = "User copy",
            category = "meeting",
            description = "d",
            author = "me",
            system_prompt = "Instructions:\nbody",
            uses = 0,
            builtin = 0,
            featured = 0,
            tags = "summary",
            lang = "en",
            preferred_model = null,
            preferred_endpoint_profile_id = null,
            created_at = now,
        )
        q.insertAutoflow(
            id = "flow-dup-ref",
            name = "Uses dropped id",
            enabled = 1,
            wake_phrase = null,
            duration_min_seconds = 0,
            duration_max_seconds = 3600,
            source_filter = "all",
            template_id = oldId,
            auto_label_voice = 0,
            language = "auto",
            notify = 1,
            post_action = null,
            model_id = null,
            endpoint_profile_id = null,
        )
        q.insertSummaryVersion(
            id = "ver-dup-ref",
            recording_id = "rec-x",
            template_id = oldId,
            template_name = "Dropped dup",
            markdown = "## notes",
            created_at = now,
            is_active = 1,
        )

        assertTrue(SeedData.maybeReconcileBodyDedupes(db))
        assertEquals(
            "1",
            q.getMeta(TemplateBodyDedupeRemap.META_KEY).executeAsOneOrNull(),
        )
        assertNull(q.selectTemplateById(oldId).executeAsOneOrNull(), "builtin dup must be deleted")
        assertTrue(q.selectTemplateById(survivorId).executeAsOneOrNull() != null)
        assertTrue(
            q.selectTemplateById("user.custom.$oldId").executeAsOneOrNull() != null,
            "user template must remain",
        )
        val flow = q.selectAutoflows().executeAsList().single { it.id == "flow-dup-ref" }
        assertEquals(survivorId, flow.template_id)
        val version = q.getSummaryVersionById("ver-dup-ref", "rec-x").executeAsOne()
        assertEquals(survivorId, version.template_id)

        // Second pass is a no-op.
        assertTrue(!SeedData.maybeReconcileBodyDedupes(db))
    }

    @Test
    fun reconcile_keeps_refs_when_custom_row_reuses_removed_plaud_id() = runTest {
        val handle = createDatabase(DriverFactory())
        val db = handle.database
        val q = db.velumQueries
        q.setMeta(TemplateBodyDedupeRemap.META_KEY, "0")

        val (oldId, survivorId) = TemplateBodyDedupeRemap.OldToSurvivor.entries.first()
        val now = 1_700_000_000L
        // Custom template occupying the removed catalog id.
        q.insertTemplate(
            id = oldId,
            name = "User reused id",
            category = "meeting",
            description = "mine",
            author = "me",
            system_prompt = "Instructions:\ncustom body",
            uses = 3,
            builtin = 0,
            featured = 0,
            tags = "summary",
            lang = "en",
            preferred_model = null,
            preferred_endpoint_profile_id = null,
            created_at = now,
        )
        q.insertTemplate(
            id = survivorId,
            name = "Survivor",
            category = "meeting",
            description = "d",
            author = "Velum",
            system_prompt = "Instructions:\nbody",
            uses = 1,
            builtin = 1,
            featured = 0,
            tags = "summary",
            lang = "en",
            preferred_model = null,
            preferred_endpoint_profile_id = null,
            created_at = now,
        )
        q.insertAutoflow(
            id = "flow-custom-id",
            name = "Points at custom plaud id",
            enabled = 1,
            wake_phrase = null,
            duration_min_seconds = 0,
            duration_max_seconds = 3600,
            source_filter = "all",
            template_id = oldId,
            auto_label_voice = 0,
            language = "auto",
            notify = 1,
            post_action = null,
            model_id = null,
            endpoint_profile_id = null,
        )
        q.insertSummaryVersion(
            id = "ver-custom-id",
            recording_id = "rec-y",
            template_id = oldId,
            template_name = "User reused id",
            markdown = "## notes",
            created_at = now,
            is_active = 1,
        )

        assertTrue(SeedData.maybeReconcileBodyDedupes(db))
        val custom = q.selectTemplateById(oldId).executeAsOne()
        assertEquals(0L, custom.builtin)
        assertEquals("User reused id", custom.name)
        val flow = q.selectAutoflows().executeAsList().single { it.id == "flow-custom-id" }
        assertEquals(oldId, flow.template_id, "custom id refs must not remap")
        val version = q.getSummaryVersionById("ver-custom-id", "rec-y").executeAsOne()
        assertEquals(oldId, version.template_id, "custom id refs must not remap")
        assertTrue(q.selectTemplateById(survivorId).executeAsOneOrNull() != null)
    }
}
