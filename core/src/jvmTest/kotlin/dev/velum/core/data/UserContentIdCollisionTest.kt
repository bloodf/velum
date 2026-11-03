package dev.velum.core.data

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class UserContentIdCollisionTest {
    private companion object {
        const val SEED = 20_261_103
    }

    // Same second for every id, so only the 10-digit suffix (9.0e9 values) separates them. With an unseeded
    // Random, 2,000 ids collide with probability ~2.2e-4 per type (2000*1999/2 pairs / 9.0e9), ~6.7e-4 per
    // run: a rare flake. A fixed seed makes the draw reproducible; a narrower suffix range still collides.
    @Test
    fun templateMemoryGlossary_useWideRandomSpace_noCollisionsIn2k() {
        val random = Random(SEED)
        val templates = (0 until 2_000).map {
            UserContentIds.template(nowEpochSeconds = 1_700_000_000L, random = random)
        }.toSet()
        val memories = (0 until 2_000).map {
            UserContentIds.memory(nowEpochSeconds = 1_700_000_000L, random = random)
        }.toSet()
        val glossary = (0 until 2_000).map {
            UserContentIds.glossary(nowEpochSeconds = 1_700_000_000L, random = random)
        }.toSet()
        assertEquals(2_000, templates.size)
        assertEquals(2_000, memories.size)
        assertEquals(2_000, glossary.size)
        val tplRe = Regex("""^tpl-user-\d+-\d{10}$""")
        val memRe = Regex("""^mem-\d+-\d{10}$""")
        val glossRe = Regex("""^gloss-\d+-\d{10}$""")
        assertTrue(templates.all { id -> tplRe.matches(id) })
        assertTrue(memories.all { id -> memRe.matches(id) })
        assertTrue(glossary.all { id -> glossRe.matches(id) })
    }

    @Test
    fun insertOrReplace_withCollidingShortTemplateId_wouldOverwrite_regressionGuard() = runBlocking {
        val repo = VelumRepository(createDatabase(DriverFactory()))
        val shortId = "tpl-user-12345"
        repo.upsertTemplate(
            id = shortId,
            name = "Keep me",
            category = "mine",
            description = "original",
            systemPrompt = "prompt-a",
        )
        repo.upsertTemplate(
            id = shortId,
            name = "Clobber",
            category = "mine",
            description = "gone",
            systemPrompt = "prompt-b",
        )
        assertEquals("Clobber", repo.getTemplate(shortId)?.name)
        assertEquals("prompt-b", repo.getTemplate(shortId)?.system_prompt)
    }
}
