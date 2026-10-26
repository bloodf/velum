package dev.velum.core.search

import dev.velum.core.db.Recording
import dev.velum.core.db.Template

/**
 * Prefer SQLite FTS5 when the index answers; fall back to [ListSearch] otherwise.
 *
 * [ftsOrderedIds] is the result of an FTS MATCH query (already executed by the caller).
 * Pass null to skip FTS and use [ListSearch] only.
 */
object RecordingSearch {
    fun recordings(
        items: List<Recording>,
        query: String,
        ftsOrderedIds: List<String>? = null,
    ): List<Recording> {
        val q = query.trim()
        if (q.isEmpty()) return items
        if (ftsOrderedIds != null && ftsOrderedIds.isNotEmpty()) {
            val byId = items.associateBy { it.id }
            val fromFts = ftsOrderedIds.mapNotNull { byId[it] }
            if (fromFts.isNotEmpty()) return fromFts
        }
        return ListSearch.filter(items, q) {
            listOf(it.title, it.transcript, it.summary_markdown)
        }
    }

    fun templates(
        items: List<Template>,
        query: String,
        ftsOrderedIds: List<String>? = null,
    ): List<Template> {
        val q = query.trim()
        if (q.isEmpty()) return items
        if (ftsOrderedIds != null && ftsOrderedIds.isNotEmpty()) {
            val byId = items.associateBy { it.id }
            val fromFts = ftsOrderedIds.mapNotNull { byId[it] }
            if (fromFts.isNotEmpty()) return fromFts
        }
        return ListSearch.filter(items, q) {
            listOf(it.name, it.description, it.category, it.author, it.system_prompt)
        }
    }
}
