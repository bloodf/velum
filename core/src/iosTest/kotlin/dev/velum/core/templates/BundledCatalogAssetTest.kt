package dev.velum.core.templates

import dev.velum.core.autoflow.AutoFlowTemplateAsset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class BundledCatalogAssetTest {
    @Test
    fun bundledCatalogsContainActualJsonRecords() {
        listOf(
            AutoFlowTemplateAsset.readJsonl(),
            PlaudTemplateAsset.readJsonl(),
        ).forEach { text ->
            val records = text.lineSequence().filter { it.isNotBlank() && !it.trimStart().startsWith("#") }.toList()
            assertTrue(records.isNotEmpty())
            records.forEach { assertTrue(Json.parseToJsonElement(it).jsonObject.isNotEmpty()) }
        }
    }

    @Test
    fun checkedInContributedCatalogIsEmpty() {
        assertEquals("", VelumContributedAsset.readJsonl())
    }

    @Test
    fun streamingGzipMatchesFullReadAndPreservesRecords() {
        val expected = PlaudFullTemplateAsset.readJsonl().lineSequence()
            .map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
        assertTrue(expected.isNotEmpty())
        var index = 0
        PlaudFullTemplateAsset.forEachLine { line ->
            assertEquals(expected[index++], line)
            assertTrue(Json.parseToJsonElement(line).jsonObject.isNotEmpty())
        }
        assertEquals(expected.size, index)
    }

    @Test
    fun callbackCanStopIteration() {
        class Stop : RuntimeException()
        var calls = 0
        try {
            PlaudFullTemplateAsset.forEachLine {
                calls++
                throw Stop()
            }
        } catch (_: Stop) {
            // The reader must propagate cancellation and close its gzip handle.
        }
        assertEquals(1, calls)
    }
}
