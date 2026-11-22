package dev.velum.core.integrations

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Markdown (as Velum's templates and transcripts produce it) to Notion blocks (#59): headings, bulleted/numbered
 * lists, to-dos, quotes, code, dividers and paragraphs, with bold/italic/strike/code/link annotations. Notion caps a
 * text object at 2000 characters, so long text is split.
 */
object NotionBlocks {
    const val TEXT_MAX = 2000

    fun fromMarkdown(markdown: String): List<JsonObject> {
        val blocks = ArrayList<JsonObject>()
        val paragraph = StringBuilder()
        fun flushParagraph() {
            if (paragraph.isNotBlank()) splitText(paragraph.toString(), TEXT_MAX).forEach { blocks += block("paragraph", it) }
            paragraph.clear()
        }
        val lines = markdown.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trimEnd()
            val fence = Regex("^\\s*```").find(line)
            val heading = Regex("^\\s{0,3}(#{1,6})\\s+(.*)$").matchEntire(line)
            val todo = Regex("^\\s*[-*+]\\s+\\[([ xX])]\\s+(.*)$").matchEntire(line)
            val bullet = Regex("^\\s*[-*+]\\s+(.*)$").matchEntire(line)
            val numbered = Regex("^\\s*\\d+[.)]\\s+(.*)$").matchEntire(line)
            val quote = Regex("^\\s*>\\s?(.*)$").matchEntire(line)
            when {
                fence != null -> {
                    flushParagraph()
                    val code = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                        if (code.isNotEmpty()) code.append('\n')
                        code.append(lines[i])
                        i++
                    }
                    splitText(code.toString(), TEXT_MAX).forEach { blocks += codeBlock(it) }
                }
                line.isBlank() -> flushParagraph()
                heading != null -> {
                    flushParagraph()
                    val level = heading.groupValues[1].length.coerceAtMost(3)
                    blocks += block("heading_$level", heading.groupValues[2].take(TEXT_MAX))
                }
                todo != null -> {
                    flushParagraph()
                    blocks += block("to_do", todo.groupValues[2].take(TEXT_MAX), checked = todo.groupValues[1].isNotBlank())
                }
                bullet != null && !Regex("^\\s*(-{3,}|\\*{3,})\\s*$").matches(line) -> {
                    flushParagraph()
                    blocks += block("bulleted_list_item", bullet.groupValues[1].take(TEXT_MAX))
                }
                numbered != null -> {
                    flushParagraph()
                    blocks += block("numbered_list_item", numbered.groupValues[1].take(TEXT_MAX))
                }
                quote != null -> {
                    flushParagraph()
                    blocks += block("quote", quote.groupValues[1].take(TEXT_MAX))
                }
                Regex("^\\s*(-{3,}|\\*{3,}|_{3,})\\s*$").matches(line) -> {
                    flushParagraph()
                    blocks += buildJsonObject { put("object", "block"); put("type", "divider"); put("divider", buildJsonObject { }) }
                }
                else -> {
                    if (paragraph.isNotEmpty()) paragraph.append('\n')
                    paragraph.append(line)
                }
            }
            i++
        }
        flushParagraph()
        return blocks
    }

    fun heading2(text: String): JsonObject = block("heading_2", text.take(TEXT_MAX))

    private fun block(type: String, text: String, checked: Boolean? = null): JsonObject = buildJsonObject {
        put("object", "block")
        put("type", type)
        put(type, buildJsonObject {
            put("rich_text", richText(text))
            if (checked != null) put("checked", checked)
        })
    }

    private fun codeBlock(text: String): JsonObject = buildJsonObject {
        put("object", "block")
        put("type", "code")
        put("code", buildJsonObject {
            put("rich_text", buildJsonArray { add(textObject(text, Style())) })
            put("language", "plain text")
        })
    }

    private data class Style(
        val bold: Boolean = false,
        val italic: Boolean = false,
        val strike: Boolean = false,
        val code: Boolean = false,
        val link: String? = null,
    )

    private fun textObject(content: String, style: Style): JsonObject = buildJsonObject {
        put("type", "text")
        put("text", buildJsonObject {
            put("content", content)
            if (style.link != null) put("link", buildJsonObject { put("url", style.link) })
        })
        if (style.bold || style.italic || style.strike || style.code) {
            put("annotations", buildJsonObject {
                if (style.bold) put("bold", true)
                if (style.italic) put("italic", true)
                if (style.strike) put("strikethrough", true)
                if (style.code) put("code", true)
            })
        }
    }

    private val INLINE = listOf(
        Regex("`([^`]+)`") to { s: Style -> s.copy(code = true) },
        Regex("\\*\\*(.+?)\\*\\*") to { s: Style -> s.copy(bold = true) },
        Regex("__(.+?)__") to { s: Style -> s.copy(bold = true) },
        Regex("~~(.+?)~~") to { s: Style -> s.copy(strike = true) },
        Regex("(?<![*\\w])\\*(?!\\s)(.+?)(?<!\\s)\\*(?![*\\w])") to { s: Style -> s.copy(italic = true) },
        Regex("(?<![_\\w])_(?!\\s)(.+?)(?<!\\s)_(?![_\\w])") to { s: Style -> s.copy(italic = true) },
    )
    private val LINK = Regex("\\[([^\\]]+)]\\((https?://[^)\\s]+)\\)")

    /** Inline Markdown to Notion rich text, each piece at most [TEXT_MAX] characters. */
    fun richText(text: String): JsonElement = buildJsonArray {
        spans(text, Style()).forEach { (content, style) ->
            splitText(content, TEXT_MAX).forEach { add(textObject(it, style)) }
        }
    }

    private fun spans(text: String, style: Style): List<Pair<String, Style>> {
        if (text.isEmpty()) return emptyList()
        if (style.code) return listOf(text to style)
        // The earliest match wins; the text before it is plain, the match is styled (recursively), then the rest.
        var best: Triple<MatchResult, Style, Boolean>? = null
        LINK.find(text)?.let { best = Triple(it, style.copy(link = it.groupValues[2]), true) }
        for ((re, apply) in INLINE) {
            val m = re.find(text) ?: continue
            if (best == null || m.range.first < best!!.first.range.first) best = Triple(m, apply(style), false)
        }
        val (m, inner, _) = best ?: return listOf(text to style)
        val content = m.groupValues[1]
        return listOfNotNull(text.substring(0, m.range.first).takeIf { it.isNotEmpty() }?.let { it to style }) +
            spans(content, inner) +
            spans(text.substring(m.range.last + 1), style)
    }

    internal fun splitText(text: String, max: Int): List<String> {
        if (text.length <= max) return listOf(text)
        val out = ArrayList<String>()
        var rest = text
        while (rest.length > max) {
            // Prefer a line break, then a space, so words are not cut.
            val cut = rest.lastIndexOf('\n', max).takeIf { it > max / 2 }
                ?: rest.lastIndexOf(' ', max).takeIf { it > max / 2 }
                ?: max
            out += rest.substring(0, cut)
            rest = rest.substring(cut).trimStart('\n', ' ')
        }
        if (rest.isNotEmpty()) out += rest
        return out
    }

    /**
     * The body of a new page in the user's database: the note's blocks, at most 100 (the rest is appended after the
     * page exists). [titleProperty] is the database's title column; [dateProperty], when the database has a date
     * column, receives the recording start.
     */
    fun createPageRequest(
        databaseId: String,
        titleProperty: String,
        title: String,
        dateProperty: String?,
        startIso: String?,
        children: List<JsonObject>,
    ): JsonObject = buildJsonObject {
        put("parent", buildJsonObject { put("database_id", databaseId) })
        put("properties", buildJsonObject {
            put(titleProperty, buildJsonObject {
                put("title", buildJsonArray { add(textObject(title.ifBlank { "Velum note" }.take(TEXT_MAX), Style())) })
            })
            if (dateProperty != null && startIso != null) {
                put(dateProperty, buildJsonObject { put("date", buildJsonObject { put("start", startIso) }) })
            }
        })
        put("children", buildJsonArray { children.take(100).forEach { add(it) } })
    }

    /** Page content: the summary, then the transcript under its own heading. */
    fun noteBlocks(note: SharedNote, summaryHeading: String, transcriptHeading: String): List<JsonObject> = buildList {
        val summary = note.summaryMarkdown?.trim().orEmpty()
        val transcript = note.transcriptMarkdown?.trim().orEmpty()
        if (summary.isNotEmpty()) {
            add(heading2(summaryHeading))
            addAll(fromMarkdown(summary))
        }
        if (transcript.isNotEmpty()) {
            add(heading2(transcriptHeading))
            addAll(fromMarkdown(transcript))
        }
    }
}
