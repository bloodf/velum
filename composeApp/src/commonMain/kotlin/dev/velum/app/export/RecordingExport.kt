package dev.velum.app.export

/**
 * Formats a recording transcript / summary for share and file export.
 * Pure formatting — no I/O.
 */
object RecordingExport {
    fun markdown(
        title: String,
        transcript: String,
        summary: String = "",
    ): String = buildString {
        append("# ").append(title.trim().ifBlank { "Untitled" }).append('\n').append('\n')
        if (transcript.isNotBlank()) {
            append("## Transcript\n\n")
            append(transcript.trim()).append('\n')
        }
        if (summary.isNotBlank()) {
            if (transcript.isNotBlank()) append('\n')
            append("## Summary\n\n")
            append(summary.trim()).append('\n')
        }
    }

    /** Strip common Markdown markers for a plain-text share. */
    fun plainText(markdown: String): String {
        if (markdown.isBlank()) return ""
        val withoutCode = markdown
            .replace(Regex("```[\\s\\S]*?```"), "")
            .replace(Regex("`([^`]+)`"), "$1")
        val withoutLinks = withoutCode
            .replace(Regex("!\\[([^\\]]*)]\\([^)]*\\)"), "$1")
            .replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1")
        val lines = withoutLinks.lineSequence().map { line ->
            line
                .replace(Regex("^#{1,6}\\s*"), "")
                .replace(Regex("^\\s*[-*+]\\s+"), "")
                .replace(Regex("^\\s*\\d+\\.\\s+"), "")
                .replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1")
                .replace(Regex("__([^_]+)__"), "$1")
                .replace(Regex("\\*([^*]+)\\*"), "$1")
                .replace(Regex("_([^_]+)_"), "$1")
                .replace(Regex("~~([^~]+)~~"), "$1")
                .trimEnd()
        }
        return lines.joinToString("\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    /**
     * Build SubRip cues from Velum transcript lines shaped like:
     * `**Name** · m:ss` (optional `h:mm:ss`) followed by spoken text.
     */
    fun srt(transcriptMarkdown: String): String {
        val cues = parseCues(transcriptMarkdown)
        if (cues.isEmpty()) return ""
        return buildString {
            cues.forEachIndexed { index, cue ->
                val endMs = cues.getOrNull(index + 1)?.startMs
                    ?: (cue.startMs + DEFAULT_CUE_DURATION_MS)
                append(index + 1).append('\n')
                append(formatSrtTimestamp(cue.startMs))
                append(" --> ")
                append(formatSrtTimestamp(endMs.coerceAtLeast(cue.startMs + 1)))
                append('\n')
                if (cue.speaker.isNotBlank()) {
                    append(cue.speaker).append(": ")
                }
                append(cue.text).append('\n').append('\n')
            }
        }.trimEnd() + "\n"
    }

    /**
     * Minimal OOXML (.docx) package: store-compressed ZIP with a single body document.
     * Accepts Markdown or plain text; Markdown is stripped first.
     */
    fun docxMinimal(markdownOrText: String): ByteArray {
        val body = plainText(markdownOrText).ifBlank { " " }
        val paragraphs = body.split('\n').map { escapeXml(it.ifEmpty { " " }) }
        val documentXml = buildString {
            append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
            append(
                """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">""",
            )
            append("<w:body>")
            paragraphs.forEach { p ->
                append("<w:p><w:r><w:t xml:space=\"preserve\">")
                append(p)
                append("</w:t></w:r></w:p>")
            }
            append("<w:sectPr/></w:body></w:document>")
        }
        val contentTypes = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
              <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
              <Default Extension="xml" ContentType="application/xml"/>
              <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
            </Types>
        """.trimIndent().replace(Regex(">\\s+<"), "><")
        val rels = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
              <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
            </Relationships>
        """.trimIndent().replace(Regex(">\\s+<"), "><")
        return ZipStoreBuilder()
            .add("[Content_Types].xml", contentTypes.encodeToByteArray())
            .add("_rels/.rels", rels.encodeToByteArray())
            .add("word/document.xml", documentXml.encodeToByteArray())
            .build()
    }

    internal data class Cue(
        val speaker: String,
        val startMs: Long,
        val text: String,
    )

    internal fun parseCues(transcriptMarkdown: String): List<Cue> {
        if (transcriptMarkdown.isBlank()) return emptyList()
        val cueHeader = Regex(
            """^\*\*(.+?)\*\*\s*·\s*(?:(\d+):)?(\d{1,2}):(\d{2})(?:[.,](\d{1,3}))?\s*$""",
        )
        val cues = mutableListOf<Cue>()
        var pendingSpeaker = ""
        var pendingStartMs = 0L
        var pendingText = StringBuilder()
        var havePending = false

        fun flush() {
            if (!havePending) return
            val text = pendingText.toString().trim()
            if (text.isNotEmpty() || pendingSpeaker.isNotEmpty()) {
                cues += Cue(
                    speaker = pendingSpeaker,
                    startMs = pendingStartMs,
                    text = text.ifBlank { "…" },
                )
            }
            pendingText = StringBuilder()
            havePending = false
        }

        transcriptMarkdown.lineSequence().forEach { raw ->
            val line = raw.trimEnd()
            val match = cueHeader.matchEntire(line.trim())
            if (match != null) {
                flush()
                pendingSpeaker = match.groupValues[1].trim()
                val hours = match.groupValues[2].toLongOrNull() ?: 0L
                val minutes = match.groupValues[3].toLongOrNull() ?: 0L
                val seconds = match.groupValues[4].toLongOrNull() ?: 0L
                val frac = match.groupValues[5]
                val millis = when {
                    frac.isEmpty() -> 0L
                    frac.length >= 3 -> frac.take(3).toLongOrNull() ?: 0L
                    else -> (frac.toLongOrNull() ?: 0L) * when (frac.length) {
                        1 -> 100L
                        2 -> 10L
                        else -> 1L
                    }
                }
                pendingStartMs = ((hours * 60L + minutes) * 60L + seconds) * 1000L + millis
                havePending = true
            } else if (havePending) {
                if (line.isNotBlank()) {
                    if (pendingText.isNotEmpty()) pendingText.append(' ')
                    pendingText.append(line.trim())
                }
            }
        }
        flush()
        return cues
    }

    private fun formatSrtTimestamp(ms: Long): String {
        val total = ms.coerceAtLeast(0L)
        val hours = total / 3_600_000L
        val minutes = (total % 3_600_000L) / 60_000L
        val seconds = (total % 60_000L) / 1000L
        val millis = total % 1000L
        return buildString {
            append(hours.toString().padStart(2, '0')).append(':')
            append(minutes.toString().padStart(2, '0')).append(':')
            append(seconds.toString().padStart(2, '0')).append(',')
            append(millis.toString().padStart(3, '0'))
        }
    }

    private fun escapeXml(value: String): String = buildString(value.length) {
        value.forEach { ch ->
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(ch)
            }
        }
    }

    private const val DEFAULT_CUE_DURATION_MS = 3_000L
}

/** Copy [text] to the system clipboard when available. */
expect fun copyToClipboard(text: String)

/**
 * Share raw [bytes] via the platform share sheet / save path.
 * Android writes a cache file and fires ACTION_SEND through FileProvider.
 */
expect fun shareBytes(filename: String, mime: String, bytes: ByteArray)
