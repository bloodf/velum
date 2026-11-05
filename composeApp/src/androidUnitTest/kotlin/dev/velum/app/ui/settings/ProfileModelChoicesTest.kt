package dev.velum.app.ui.settings

import dev.velum.core.model.AiModelInfo
import kotlin.test.Test
import kotlin.test.assertEquals

class ProfileModelChoicesTest {
    @Test
    fun choicesFollowBoundProfileAndEmptyResultsPermitManualEntry() {
        val alpha = listOf(AiModelInfo("alpha-model", "alpha-owner"))
        val beta = listOf(AiModelInfo("beta-model", "beta-owner"))
        val caches = mapOf("alpha" to alpha, "beta" to beta, "empty" to emptyList())

        assertEquals(alpha, profileModelChoices(caches, "alpha", ""))
        assertEquals(beta, profileModelChoices(caches, "beta", ""))
        assertEquals(beta, profileModelChoices(caches, "beta", " BETA-OWNER "))
        assertEquals(emptyList(), profileModelChoices(caches, "missing", ""))
        assertEquals(emptyList(), profileModelChoices(caches, "empty", ""))
        assertEquals(emptyList(), profileModelChoices(caches, "beta", "alpha"))
    }
}
