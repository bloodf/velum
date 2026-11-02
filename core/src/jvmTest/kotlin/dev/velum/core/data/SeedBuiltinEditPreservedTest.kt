package dev.velum.core.data

import dev.velum.core.templates.BuiltinTemplateCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** #100: a seed bump refreshes builtins but must not replace a builtin the user edited (same id, builtin = 0). */
class SeedBuiltinEditPreservedTest {
    @Test
    fun seedBump_keepsUserEditedBuiltinAndLegacyRows() = runTest {
        val handle = createDatabase(DriverFactory())
        val repo = VelumRepository(handle)
        val q = handle.database.velumQueries
        val builtinId = SeedData.builtinTemplates().first().id
        // Editing a builtin in the template screen keeps its id and stores builtin = 0.
        repo.upsertTemplate(builtinId, "Synthetic edit", "meeting", "", "SYNTHETIC USER PROMPT", builtin = false)
        val legacyId = BuiltinTemplateCatalog.LegacyAliases.keys.first()
        repo.upsertTemplate(legacyId, "Synthetic legacy edit", "meeting", "", "SYNTHETIC LEGACY PROMPT", builtin = false)

        q.setMeta("demo_seed_version", "0")
        assertTrue(SeedData.maybeSeed(handle.database))

        val edited = q.selectTemplateById(builtinId).executeAsOne()
        assertEquals(0L, edited.builtin)
        assertEquals("SYNTHETIC USER PROMPT", edited.system_prompt)
        assertEquals("SYNTHETIC LEGACY PROMPT", q.selectTemplateById(legacyId).executeAsOne().system_prompt)
        // Untouched builtins are still refreshed.
        val other = SeedData.builtinTemplates()[1]
        assertEquals(1L, q.selectTemplateById(other.id).executeAsOne().builtin)
    }
}
