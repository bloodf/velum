package dev.velum.core.templates

import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * #99: the exact-body dedupe rule, pinned in JVM CI.
 *
 * The catalog is deduplicated by tools/templates/clean.py (`dedupe_exact_bodies`), not by Kotlin,
 * and no workflow runs its Python tests. Both sides therefore run the SAME golden cases from
 * tools/templates/testdata/dedupe_rule_cases.json: test_catalog_dedupe.py against clean.py, and this
 * test against [ExactBodyDedupe], a restatement of the rule that works on the raw JSON rows so the
 * canonical tie-break (sorted-keys JSON, Python separators) is byte-for-byte the same. Change the
 * rule in clean.py and the golden file together, or one side fails. The shipped Full catalog is also
 * checked to be a fixed point of the rule.
 *
 * Rule: one survivor per exact body (no whitespace or case normalization), chosen by official first,
 * then Featured, then name in code point order, then canonical row JSON. The survivor keeps official
 * status if any collapsed row had it, and Featured only when its own row has a `featured` key
 * (clean.py's behavior; a survivor without that key never gains one).
 */
class TemplateDedupeRuleTest {
    private val goldenCases: List<JsonObject> =
        Json.parseToJsonElement(repoFile("tools/templates/testdata/dedupe_rule_cases.json").readText()).jsonArray.map { it.jsonObject }

    @Test
    fun every_golden_case_matches_the_expected_survivors_aliases_and_flags() {
        assertTrue(goldenCases.size >= 12, "golden cases missing: ${goldenCases.size}")
        for (case in goldenCases) {
            val name = case.getValue("name").jsonPrimitive.content
            val rows = case.getValue("rows").jsonArray.map { it.jsonObject }
            val featuredIds = case.getValue("featured_ids").jsonArray.mapTo(HashSet()) { it.jsonPrimitive.content }
            val res = ExactBodyDedupe.run(rows, featuredIds)
            assertEquals(
                case.getValue("expected_survivor_ids").jsonArray.map { it.jsonPrimitive.content },
                res.survivors.map { it.id() },
                "survivors: $name",
            )
            assertEquals(
                case.getValue("expected_aliases").jsonObject.mapValues { it.value.jsonPrimitive.content },
                res.aliases,
                "aliases: $name",
            )
            case["expected_flags"]?.jsonObject?.forEach { (tid, flags) ->
                val survivor = res.survivors.first { it.id() == tid }
                flags.jsonObject.forEach { (key, want) -> assertEquals(want, survivor[key] ?: JsonNull, "$name: $tid.$key") }
            }
        }
    }

    @Test
    fun shuffled_input_gives_identical_survivors_and_order() {
        val rows = (0 until 40).map { i ->
            row("id$i", "Name ${i % 7}", "$BODY\n- variant ${i % 5}", official = i == 11)
        }
        val baseline = ExactBodyDedupe.run(rows)
        for (seed in 0 until 5) {
            val again = ExactBodyDedupe.run(rows.shuffled(Random(seed)))
            assertEquals(baseline.survivors, again.survivors)
            assertEquals(baseline.aliases, again.aliases)
        }
    }

    @Test
    fun a_second_run_changes_nothing() {
        val once = ExactBodyDedupe.run(listOf(row("b", "Beta"), row("a", "Alpha"), row("c", "Gamma", "$BODY!")))
        val twice = ExactBodyDedupe.run(once.survivors)
        assertEquals(once.survivors, twice.survivors)
        assertEquals(0, twice.aliases.size)
    }

    @Test
    fun shipped_full_catalog_is_a_fixed_point_of_the_rule_in_any_order() {
        val rows = parseRows(PlaudFullTemplateAsset.readJsonl())
        assertTrue(rows.size > 1000, "full catalog missing: ${rows.size}")
        // Every non-blank, non-# line of the asset is a row the app also parses.
        assertEquals(rows.size, PlaudTemplateCatalog.parseJsonl(PlaudFullTemplateAsset.readJsonl()).size)
        val featuredIds = parseRows(PlaudTemplateAsset.readJsonl()).mapTo(HashSet()) { it.id() }
        val once = ExactBodyDedupe.run(rows, featuredIds)
        assertEquals(0, once.aliases.size, "shipped catalog still holds exact-body duplicates: ${once.aliases.entries.take(3)}")
        assertEquals(rows.size, once.survivors.size)
        assertEquals(rows.mapTo(HashSet()) { it.id() }, once.survivors.mapTo(HashSet()) { it.id() })
        val shuffled = ExactBodyDedupe.run(rows.shuffled(Random(99)), featuredIds)
        assertEquals(once.survivors.map { it.id() }, shuffled.survivors.map { it.id() }, "survivor order must not depend on input order")
    }

    @Test
    fun every_alias_resolves_in_one_step_to_a_shipped_template() {
        val shipped = parseRows(PlaudFullTemplateAsset.readJsonl()).mapTo(HashSet()) { it.id() }
        val aliases = PlaudTemplateAliases.RemovedDuplicates
        aliases.forEach { (removed, survivor) ->
            assertFalse(removed in shipped, "$removed is both removed and shipped")
            assertFalse(survivor in aliases, "alias $removed points at another alias $survivor (chain)")
            assertEquals(survivor, BuiltinTemplateCatalog.resolveId(removed))
            assertTrue(survivor in shipped, "$removed -> $survivor, survivor not shipped")
        }
    }

    private fun parseRows(text: String): List<JsonObject> =
        text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { Json.parseToJsonElement(it).jsonObject }.toList()

    private fun row(id: String, name: String, body: String = BODY, official: Boolean = false): JsonObject = JsonObject(
        mapOf(
            "id" to JsonPrimitive(id), "name" to JsonPrimitive(name), "body" to JsonPrimitive(body),
            "official" to JsonPrimitive(official), "category" to JsonPrimitive("meeting"),
            "bodyType" to JsonPrimitive("prompt"), "usageCount" to JsonPrimitive(0),
        ),
    )

    private fun JsonObject.id() = getValue("id").jsonPrimitive.content

    private fun repoFile(rel: String): File =
        listOf(File(rel), File("../$rel"), File("../../$rel")).firstOrNull { it.isFile } ?: error("missing $rel")

    private companion object {
        const val BODY = "## Summary\n- Key points discussed in the meeting\n- Action items with owners"
    }
}

/** Restatement of tools/templates/clean.py `dedupe_exact_bodies` over raw JSON rows (see the class comment above). */
private object ExactBodyDedupe {
    class Result(val survivors: List<JsonObject>, val aliases: Map<String, String>)

    private fun text(r: JsonObject, key: String) = (r[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    /** clean.py parse_bool: a real bool, or a string in {1, true, yes, y} after strip/lowercase. */
    private fun parseBool(e: JsonElement?): Boolean {
        val p = e as? JsonPrimitive ?: return false
        if (p is JsonNull) return false
        return p.content.trim().lowercase() in setOf("1", "true", "yes", "y")
    }

    /** json.dumps(row, ensure_ascii=False, sort_keys=True, separators=(",", ":")); keys sort by code point. */
    private fun canonical(r: JsonObject): String =
        JsonObject(r.toSortedMap(::codePointCompare)).toString()

    /** Code point order, like Python str comparison (String.compareTo is UTF-16 order). */
    private fun codePointCompare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val x = a.codePointAt(i)
            val y = b.codePointAt(j)
            if (x != y) return x.compareTo(y)
            i += Character.charCount(x)
            j += Character.charCount(y)
        }
        return (a.length - i).compareTo(b.length - j)
    }

    fun run(rows: List<JsonObject>, featuredIds: Set<String> = emptySet()): Result {
        fun featured(r: JsonObject) = parseBool(r["featured"]) || text(r, "id") in featuredIds
        val rank = Comparator<JsonObject> { a, b ->
            compareValuesBy(a, b, { if (parseBool(it["official"])) 0 else 1 }, { if (featured(it)) 0 else 1 }).takeIf { it != 0 }
                ?: codePointCompare(text(a, "name").orEmpty(), text(b, "name").orEmpty()).takeIf { it != 0 }
                ?: codePointCompare(canonical(a), canonical(b))
        }
        val survivors = ArrayList<JsonObject>()
        val aliases = sortedMapOf<String, String>()
        rows.groupBy { text(it, "body") }.values.forEach { members ->
            val sorted = members.sortedWith(rank)
            var best = sorted.first()
            if (sorted.size > 1) {
                val extra = HashMap<String, JsonElement>()
                if (sorted.any { parseBool(it["official"]) }) extra["official"] = JsonPrimitive(true)
                if ("featured" in best && sorted.any { parseBool(it["featured"]) }) extra["featured"] = JsonPrimitive(true)
                best = JsonObject(best + extra)
                sorted.drop(1).forEach { m ->
                    val mid = text(m, "id")
                    if (!mid.isNullOrEmpty() && mid != text(best, "id")) aliases[mid] = text(best, "id")!!
                }
            }
            survivors += best
        }
        survivors.sortWith(rank)
        return Result(survivors, aliases)
    }
}
