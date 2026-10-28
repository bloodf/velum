package dev.velum.core.autoflow

import dev.velum.core.templates.BuiltinTemplateCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

class AutoFlowTemplateCatalogTest {
    @Test
    fun bundledCatalogHasAtLeast100DistinctUsefulTemplates() {
        val all = AutoFlowTemplateCatalog.All
        assertTrue(all.size >= 100, "expected ≥100 AutoFlow templates, got ${all.size}")
        val ids = all.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "duplicate AutoFlow template ids")
    }

    @Test
    fun everyTemplateMatchesSchemaInvariants() {
        val noteIds = BuiltinTemplateCatalog.All.map { it.id }.toSet()
        val allowedPosts = setOf(null, "", "share", "calendar_insert", "folder_export", "webhook")
        val allowedSources = setOf("note_pro", "phone", "import", "all")
        val allowedLangs = setOf("auto", "pt-BR", "en")
        val allowedIcons = setOf(
            "meeting", "sales", "interview", "medical", "legal", "education",
            "journal", "productivity", "research", "content", "estate", "finance", "code", "device",
        )

        AutoFlowTemplateCatalog.All.forEach { t ->
            assertEquals(AUTOFLOW_TEMPLATE_SCHEMA_VERSION, t.schemaVersion, t.id)
            assertTrue(AutoFlowTemplateCategories.isKnown(t.category), "bad category ${t.category} in ${t.id}")
            assertTrue(t.icon in allowedIcons, "bad icon ${t.icon} in ${t.id}")
            assertTrue(t.name.en.isNotBlank() && t.name.ptBr.isNotBlank(), "name locales ${t.id}")
            assertTrue(t.description.en.isNotBlank() && t.description.ptBr.isNotBlank(), "desc locales ${t.id}")
            assertTrue(t.preview.en.isNotBlank() && t.preview.ptBr.isNotBlank(), "preview locales ${t.id}")
            val d = t.definition
            assertTrue(d.sourceFilter in allowedSources, "source ${d.sourceFilter} in ${t.id}")
            assertTrue(d.language in allowedLangs, "language ${d.language} in ${t.id}")
            assertTrue(d.postAction in allowedPosts, "post_action ${d.postAction} in ${t.id}")
            assertTrue(d.durationMinSeconds <= d.durationMaxSeconds, "duration range ${t.id}")
            assertTrue(
                d.templateId in noteIds,
                "unknown note template_id ${d.templateId} in ${t.id}",
            )
            // Post-action string must round-trip through the runtime enum.
            val stored = d.normalizedPostAction()
            assertEquals(stored, AutoFlowPostAction.fromStored(stored).stored.takeIf { it.isNotEmpty() })
        }
    }

    @Test
    fun everyCategoryHasTemplatesAndOrderIsStable() {
        val present = AutoFlowTemplateCatalog.categoriesPresent()
        assertTrue(present.size >= 10, "expected many categories, got $present")
        present.forEach { key ->
            assertTrue(
                AutoFlowTemplateCatalog.All.any { it.category == key },
                "missing templates for $key",
            )
        }
        // Order list is a prefix-stable catalog contract.
        assertEquals(
            AutoFlowTemplateCategories.Order.filter { it in present },
            present,
        )
    }

    @Test
    fun searchFiltersByCategoryAndQuery() {
        val meetings = AutoFlowTemplateCatalog.search("", category = "meetings")
        assertTrue(meetings.isNotEmpty())
        assertTrue(meetings.all { it.category == "meetings" })
        val hit = AutoFlowTemplateCatalog.search("Obsidian", languageTag = "en")
        assertTrue(hit.any { it.id.contains("obsidian") || it.preview.en.contains("Obsidian", ignoreCase = true) })
    }

    @Test
    fun byIdResolvesKnownTemplate() {
        val first = AutoFlowTemplateCatalog.All.first()
        assertNotNull(AutoFlowTemplateCatalog.byId(first.id))
    }

    @Test
    fun builtinTemplatesHaveNoAuthorCredit() {
        val credited = AutoFlowTemplateCatalog.All.filter { it.author != null }
        if (credited.isNotEmpty()) {
            fail(
                "Built-in AutoFlow templates must omit author; credited=" +
                    credited.joinToString { it.id },
            )
        }
    }

    @Test
    fun parseJsonlRoundTripSample() {
        val sample = AutoFlowTemplateCatalog.All.take(3)
        val jsonl = sample.joinToString("\n") { line ->
            // Re-encode via kotlinx by decoding the packed asset lines is enough;
            // ensure parser accepts the packed resource format.
            kotlinx.serialization.json.Json.encodeToString(AutoFlowTemplate.serializer(), line)
        }
        val parsed = AutoFlowTemplateCatalog.parseJsonl(jsonl)
        assertEquals(sample.map { it.id }, parsed.map { it.id })
    }
}
