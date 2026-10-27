package dev.velum.ai.local

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WhistleModelReadinessTest {

    @Test
    fun whistleCatalog_hasPinnedShaAndCactLayout() {
        val spec = DefaultLocalCatalog.first { it.id == "whistle" }
        assertEquals(
            "b6e02f048568ac5d01a2042556c658061e699acbc0aa2a1439f52f3d461dffeb",
            spec.sha256,
        )
        assertTrue(spec.downloadUrl!!.contains("whistle.cact"))
        assertEquals("whistle.cact", spec.archiveFileName)
        assertEquals("Apache-2.0", spec.license)
        assertIs<ModelLayout.SingleCactFile>(ModelReadiness.layoutFor(spec))
    }
}
