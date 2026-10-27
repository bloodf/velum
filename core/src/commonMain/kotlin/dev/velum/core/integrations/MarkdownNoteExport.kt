package dev.velum.core.integrations

/**
 * Builds Obsidian-friendly Markdown with YAML frontmatter for a processed recording.
 * Pure formatting — no I/O.
 */
object MarkdownNoteExport {
    data class Note(
        val title: String,
        val startedAtEpochSeconds: Long?,
        val durationSeconds: Long,
        val source: String?,
        val speakers: List<String> = emptyList(),
        val tags: List<String> = emptyList(),
        val summaryMarkdown: String?,
        val transcriptMarkdown: String?,
    )

    fun render(note: Note): String = buildString {
        appendLine("---")
        appendLine("title: ${yamlString(note.title)}")
        note.startedAtEpochSeconds?.let { appendLine("date: $it") }
        appendLine("duration_seconds: ${note.durationSeconds}")
        note.source?.takeIf { it.isNotBlank() }?.let { appendLine("source: ${yamlString(it)}") }
        if (note.speakers.isNotEmpty()) {
            appendLine("speakers:")
            note.speakers.forEach { appendLine("  - ${yamlString(it)}") }
        }
        if (note.tags.isNotEmpty()) {
            appendLine("tags:")
            note.tags.forEach { appendLine("  - ${yamlString(it)}") }
        }
        appendLine("app: Velum")
        appendLine("---")
        appendLine()
        append("# ").append(note.title).appendLine().appendLine()
        if (!note.summaryMarkdown.isNullOrBlank()) {
            appendLine(note.summaryMarkdown.trim())
            appendLine()
        }
        if (!note.transcriptMarkdown.isNullOrBlank()) {
            appendLine("## Transcript")
            appendLine()
            appendLine(note.transcriptMarkdown.trim())
        }
    }

    fun filename(title: String, startedAtEpochSeconds: Long?): String {
        val slug = title.trim()
            .lowercase()
            .replace(Regex("[^a-z0-9à-ú]+"), "-")
            .trim('-')
            .ifBlank { "note" }
            .take(60)
        val prefix = startedAtEpochSeconds?.toString() ?: "note"
        return "$prefix-$slug.md"
    }

    private fun yamlString(value: String): String {
        val needsQuote = value.any { ch ->
            ch.isWhitespace() || ch in ":#{}[],&*?|>!%@'`\""
        }
        return if (needsQuote) {
            "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        } else {
            value
        }
    }
}
