package dev.velum.core.templates

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Kotlin [TemplateBodyDedupeRemap] must stay byte-for-byte in sync with the
 * committed remap JSON produced by `tools/templates/body_dedupe.py` (#99).
 */
class TemplateBodyDedupeRemapTest {

    @Test
    fun kotlin_remap_matches_committed_json_exactly() {
        val jsonPath = resolveRemapJson()
        assertTrue(Files.isRegularFile(jsonPath), "missing remap JSON at $jsonPath")
        val text = Files.readString(jsonPath)
        val obj = Json.parseToJsonElement(text) as JsonObject
        val fromJson = obj.entries
            .associate { (k, v) ->
                k to ((v as JsonPrimitive).contentOrNull ?: error("non-string value for $k"))
            }
            .toSortedMap()
        val fromKotlin = TemplateBodyDedupeRemap.OldToSurvivor.toSortedMap()
        assertEquals(fromJson.keys, fromKotlin.keys, "remap key sets differ")
        assertEquals(fromJson, fromKotlin, "TemplateBodyDedupeRemap != body_dedupe_remap.json")
    }

    private fun resolveRemapJson(): Path {
        val cwd = Path.of("").toAbsolutePath()
        val candidates = listOf(
            cwd.resolve("docs/templates/body_dedupe_remap.json"),
            cwd.resolve("../docs/templates/body_dedupe_remap.json"),
            cwd.resolve("../../docs/templates/body_dedupe_remap.json"),
        )
        candidates.firstOrNull { Files.isRegularFile(it) }?.let { return it }
        // Classpath copy shipped next to the gzip full catalog.
        val stream = javaClass.classLoader.getResourceAsStream("template_body_dedupe_remap.json")
            ?: error("remap JSON not found on disk or classpath")
        val tmp = Files.createTempFile("body_dedupe_remap", ".json")
        stream.use { Files.write(tmp, it.readBytes()) }
        return tmp
    }
}
