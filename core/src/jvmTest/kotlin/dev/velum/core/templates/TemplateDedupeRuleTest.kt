package dev.velum.core.templates

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #99: the exact-body dedupe rule, pinned in JVM CI.
 *
 * The catalog is deduplicated by tools/templates/clean.py (`dedupe_exact_bodies`), not by Kotlin,
 * and no workflow runs its Python tests. [ExactBodyDedupe] below is a test-side restatement of the
 * rule as written in the issue, so the rule cases (different names, precedence, tie-break,
 * whitespace/case) run on every JVM test run, and the shipped Full catalog is checked to be a fixed
 * point of it. It does not execute the Python tool: a change to clean.py is only caught here if the
 * shipped data stops satisfying the rule.
 *
 * Rule: one survivor per exact body (no whitespace or case normalization), chosen by
 * official first, then Featured, then name in code point order, then canonical row content.
 * The survivor keeps official/Featured status if any collapsed row had it.
 */
class TemplateDedupeRuleTest {
    private fun row(
        id: String,
        name: String,
        body: String = BODY,
        official: Boolean = false,
        featured: Boolean = false,
        uses: Long = 0,
    ) = TemplateCatalogEntry(
        id = id, category = "meeting", name = name, body = body,
        official = official, featured = featured, uses = uses,
    )

    private fun ids(rows: List<TemplateCatalogEntry>) = rows.map { it.id }

    @Test
    fun identical_bodies_with_different_names_collapse_to_one() {
        val res = ExactBodyDedupe.run(listOf(row("b", "Beta"), row("a", "Alpha"), row("c", "Gamma")))
        assertEquals(listOf("a"), ids(res.survivors))
        assertEquals(mapOf("b" to "a", "c" to "a"), res.aliases)
        assertEquals(listOf(3, 1, 2, 1), listOf(res.inputRows, res.duplicateGroups, res.removedRows, res.survivors.size))
    }

    @Test
    fun official_beats_featured_beats_name() {
        val all = listOf(row("a", "Alpha"), row("f", "Zeta", featured = true), row("o", "Zulu", official = true))
        assertEquals(listOf("o"), ids(ExactBodyDedupe.run(all).survivors))
        assertEquals(listOf("f"), ids(ExactBodyDedupe.run(all.take(2)).survivors))
    }

    @Test
    fun existing_featured_ids_count_as_featured() {
        val res = ExactBodyDedupe.run(listOf(row("a", "Alpha"), row("z", "Zeta")), featuredIds = setOf("z"))
        assertEquals(listOf("z"), ids(res.survivors))
    }

    @Test
    fun survivor_keeps_official_and_featured_status_from_collapsed_rows() {
        val (survivor) = ExactBodyDedupe.run(listOf(row("a", "Alpha", featured = true), row("o", "Omega", official = true))).survivors
        assertEquals("o", survivor.id)
        assertTrue(survivor.official)
        assertTrue(survivor.featured, "Featured status of the removed row carries over")
    }

    @Test
    fun same_name_ties_break_on_canonical_row_content() {
        // Canonical content lists fields in alphabetical order, so "id" decides before "uses".
        val res = ExactBodyDedupe.run(listOf(row("y", "Same", uses = 5), row("x", "Same", uses = 9)))
        assertEquals(listOf("x"), ids(res.survivors))
    }

    @Test
    fun name_order_is_plain_code_point_order() {
        // Uppercase sorts before lowercase; an astral name sorts after BMP names (UTF-16 order would not).
        assertEquals(listOf("upper"), ids(ExactBodyDedupe.run(listOf(row("lower", "alpha"), row("upper", "Beta"))).survivors))
        assertEquals(
            listOf("bmp"),
            ids(ExactBodyDedupe.run(listOf(row("astral", "😀 smile"), row("bmp", "～ tilde"))).survivors),
        )
    }

    @Test
    fun bodies_differing_only_in_whitespace_or_case_are_distinct() {
        val rows = listOf(
            row("a", "A", BODY),
            row("b", "B", "$BODY "),
            row("c", "C", BODY.uppercase()),
            row("d", "D", BODY.replace("\n", "\r\n")),
        )
        val res = ExactBodyDedupe.run(rows)
        assertEquals(4, res.survivors.size)
        assertEquals(0, res.duplicateGroups)
    }

    @Test
    fun shuffled_input_gives_identical_survivors_and_order() {
        val rows = (0 until 40).map { i -> row("id$i", "Name ${i % 7}", "$BODY\n- variant ${i % 5}", official = i == 11) }
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
        assertEquals(0, twice.removedRows)
    }

    @Test
    fun shipped_full_catalog_is_a_fixed_point_of_the_rule_in_any_order() {
        val full = PlaudTemplateCatalog.parseJsonl(PlaudFullTemplateAsset.readJsonl())
        assertTrue(full.size > 1000, "full catalog missing: ${full.size}")
        val featuredIds = PlaudTemplateCatalog.All.mapTo(HashSet()) { it.id }
        val once = ExactBodyDedupe.run(full, featuredIds)
        assertEquals(0, once.removedRows, "shipped catalog still holds exact-body duplicates: ${once.aliases.entries.take(3)}")
        assertEquals(0, once.duplicateGroups)
        assertEquals(full.mapTo(HashSet()) { it.id }, ids(once.survivors).toSet())
        val shuffled = ExactBodyDedupe.run(full.shuffled(Random(99)), featuredIds)
        assertEquals(ids(once.survivors), ids(shuffled.survivors), "survivor order must not depend on input order")
    }

    @Test
    fun every_alias_resolves_in_one_step_to_a_shipped_template() {
        val shipped = PlaudTemplateCatalog.parseJsonl(PlaudFullTemplateAsset.readJsonl()).mapTo(HashSet()) { it.id }
        val aliases = PlaudTemplateAliases.RemovedDuplicates
        aliases.forEach { (removed, survivor) ->
            assertFalse(removed in shipped, "$removed is both removed and shipped")
            assertFalse(survivor in aliases, "alias $removed points at another alias $survivor (chain)")
            assertEquals(survivor, BuiltinTemplateCatalog.resolveId(removed))
            assertTrue(survivor in shipped, "$removed -> $survivor, survivor not shipped")
        }
    }

    private companion object {
        const val BODY = "## Summary\n- Key points discussed in the meeting\n- Action items with owners"
    }
}

/** Test-side restatement of tools/templates/clean.py `dedupe_exact_bodies` (see the class comment above). */
private object ExactBodyDedupe {
    class Result(
        val survivors: List<TemplateCatalogEntry>,
        val aliases: Map<String, String>,
        val inputRows: Int,
        val duplicateGroups: Int,
        val removedRows: Int,
    )

    /** Fields in alphabetical order stand in for the Python tool's sorted-keys JSON. */
    private fun canonical(r: TemplateCatalogEntry) =
        listOf(r.body, r.category, r.description, r.featured, r.id, r.lang, r.name, r.official, r.tags, r.uses).joinToString("\u0000")

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

    fun run(rows: List<TemplateCatalogEntry>, featuredIds: Set<String> = emptySet()): Result {
        fun isFeatured(r: TemplateCatalogEntry) = r.featured || r.id in featuredIds
        val rank = Comparator<TemplateCatalogEntry> { a, b ->
            compareValuesBy(a, b, { if (it.official) 0 else 1 }, { if (isFeatured(it)) 0 else 1 }).takeIf { it != 0 }
                ?: codePointCompare(a.name, b.name).takeIf { it != 0 }
                ?: codePointCompare(canonical(a), canonical(b))
        }
        val survivors = ArrayList<TemplateCatalogEntry>()
        val aliases = sortedMapOf<String, String>()
        var groups = 0
        rows.groupBy { it.body }.values.forEach { members ->
            val sorted = members.sortedWith(rank)
            var best = sorted.first()
            if (sorted.size > 1) {
                groups++
                best = best.copy(
                    official = best.official || sorted.any { it.official },
                    featured = best.featured || sorted.any { it.featured },
                )
                sorted.drop(1).filter { it.id != best.id }.forEach { aliases[it.id] = best.id }
            }
            survivors += best
        }
        survivors.sortWith(rank)
        return Result(survivors, aliases, rows.size, groups, rows.size - survivors.size)
    }
}
