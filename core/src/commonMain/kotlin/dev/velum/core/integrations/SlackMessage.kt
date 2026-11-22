package dev.velum.core.integrations

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** What a note destination (Slack, Notion) receives from a recording. */
data class SharedNote(
    val title: String,
    /** Local date and time already formatted for people, e.g. `2026-11-22 10:39`. */
    val whenLabel: String?,
    val durationSeconds: Long,
    val speakers: List<String>,
    val summaryMarkdown: String?,
    /** Spoken transcript only (markers removed). */
    val transcriptMarkdown: String?,
)

/**
 * Slack Incoming Webhook payload for a note's summary: Block Kit with a plain-text fallback. Slack's `mrkdwn` is not
 * Markdown, so headings, bold, lists and links are rewritten; limits are respected (header 150 chars, section 3000,
 * 50 blocks). The transcript is not posted: a channel message is for the summary.
 */
object SlackMessage {
    private const val HEADER_MAX = 150
    private const val SECTION_MAX = 3000
    private const val MAX_BLOCKS = 50

    fun payload(note: SharedNote, emptySummaryText: String = "(no summary yet)"): JsonObject {
        val summary = note.summaryMarkdown?.trim().orEmpty()
        val body = if (summary.isEmpty()) emptySummaryText else toMrkdwn(summary)
        val context = listOfNotNull(
            note.whenLabel,
            note.durationSeconds.takeIf { it > 0 }?.let(::durationLabel),
            note.speakers.takeIf { it.isNotEmpty() }?.joinToString(", "),
        ).joinToString(" · ")
        val sections = chunk(body, SECTION_MAX)
        val blocks = buildJsonArray {
            add(buildJsonObject {
                put("type", "header")
                put("text", buildJsonObject {
                    put("type", "plain_text")
                    put("text", note.title.ifBlank { "Velum note" }.take(HEADER_MAX))
                })
            })
            if (context.isNotEmpty()) {
                add(buildJsonObject {
                    put("type", "context")
                    put("elements", buildJsonArray { add(mrkdwnText(escape(context))) })
                })
            }
            // Header and context take two blocks; the rest of the 50 go to the summary.
            sections.take(MAX_BLOCKS - 2).forEach { add(buildJsonObject { put("type", "section"); put("text", mrkdwnText(it)) }) }
        }
        return buildJsonObject {
            // Notifications and clients without Block Kit show this.
            put("text", note.title.ifBlank { "Velum note" }.take(HEADER_MAX))
            put("blocks", blocks)
        }
    }

    private fun mrkdwnText(text: String) = buildJsonObject {
        put("type", "mrkdwn")
        put("text", text)
    }

    /** Slack needs &, < and > escaped in text (they delimit its own links and mentions). */
    internal fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /** Markdown (as Velum's templates produce it) to Slack mrkdwn. */
    fun toMrkdwn(markdown: String): String = markdown.lines().joinToString("\n") { raw ->
        val line = raw.trimEnd()
        val heading = Regex("^\\s{0,3}#{1,6}\\s+(.*)$").matchEntire(line)
        val bullet = Regex("^(\\s*)[-*+]\\s+(.*)$").matchEntire(line)
        val todo = Regex("^(\\s*)[-*+]\\s+\\[([ xX])]\\s+(.*)$").matchEntire(line)
        when {
            heading != null -> "*${inline(heading.groupValues[1]).replace("*", "")}*"
            todo != null -> todo.groupValues[1] + (if (todo.groupValues[2].isBlank()) "☐ " else "☑ ") + inline(todo.groupValues[3])
            bullet != null -> bullet.groupValues[1] + "• " + inline(bullet.groupValues[2])
            Regex("^\\s*(-{3,}|\\*{3,}|_{3,})\\s*$").matches(line) -> "───"
            else -> inline(line)
        }
    }

    /** Bold, italic, strike, links, with the rest escaped. Code spans are kept as they are. */
    private fun inline(text: String): String {
        val out = StringBuilder()
        var rest = text
        val link = Regex("\\[([^\\]]+)]\\((https?://[^)\\s]+)\\)")
        while (rest.isNotEmpty()) {
            val m = link.find(rest)
            if (m == null) {
                out.append(styles(escape(rest)))
                break
            }
            out.append(styles(escape(rest.substring(0, m.range.first))))
            out.append('<').append(m.groupValues[2]).append('|').append(escape(m.groupValues[1]).replace("|", "¦")).append('>')
            rest = rest.substring(m.range.last + 1)
        }
        return out.toString()
    }

    private fun styles(text: String): String = text
        .replace(Regex("\\*\\*(.+?)\\*\\*"), "\u0001$1\u0001") // bold, protected from the italic pass
        .replace(Regex("__(.+?)__"), "\u0001$1\u0001")
        .replace(Regex("(?<![*\\w])\\*(?!\\s)(.+?)(?<!\\s)\\*(?![*\\w])"), "_$1_")
        .replace(Regex("~~(.+?)~~"), "~$1~")
        .replace('\u0001', '*')

    internal fun chunk(text: String, max: Int): List<String> {
        if (text.length <= max) return listOf(text)
        val parts = ArrayList<String>()
        val current = StringBuilder()
        for (line in text.lines()) {
            var l = line
            while (l.length > max) {
                if (current.isNotEmpty()) { parts += current.toString(); current.clear() }
                parts += l.take(max)
                l = l.drop(max)
            }
            if (current.length + l.length + 1 > max && current.isNotEmpty()) {
                parts += current.toString()
                current.clear()
            }
            if (current.isNotEmpty()) current.append('\n')
            current.append(l)
        }
        if (current.isNotEmpty()) parts += current.toString()
        return parts
    }

    fun durationLabel(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return when {
            h > 0 -> "${h}h ${m}min"
            m > 0 -> "${m}min"
            else -> "${s}s"
        }
    }
}
