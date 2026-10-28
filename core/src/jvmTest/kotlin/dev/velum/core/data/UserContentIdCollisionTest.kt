package dev.velum.core.data

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class UserContentIdCollisionTest {
    @Test
    fun templateMemoryGlossary_useWideRandomSpace_noCollisionsIn2k() {
        val templates = (0 until 2_000).map {
            UserContentIds.template(nowEpochSeconds = 1_700_000_000L, random = Random.Default)
        }.toSet()
        val memories = (0 until 2_000).map {
            UserContentIds.memory(nowEpochSeconds = 1_700_000_000L, random = Random.Default)
        }.toSet()
        val glossary = (0 until 2_000).map {
            UserContentIds.glossary(nowEpochSeconds = 1_700_000_000L, random = Random.Default)
        }.toSet()
        assertEquals(2_000, templates.size)
        assertEquals(2_000, memories.size)
        assertEquals(2_000, glossary.size)
        assertTrue(templates.all { it.matches(Regex("""tpl-user-\d+-\d{10}""")) })
        assertTrue(memories.all { it.matches(Regex("""mem-\d+-\d{10}""")) })
        assertTrue(glossary.all { it.matches(Regex("""gloss-\d+-\d{10}""")) })
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
