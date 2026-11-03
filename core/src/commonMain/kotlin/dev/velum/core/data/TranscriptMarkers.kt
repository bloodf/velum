package dev.velum.core.data

/**
 * Live-capture markers are the user's own notes appended to a transcript as a trailing section. They are
 * not speech: they must not reach prompts, the search index or automation rules.
 */
object TranscriptMarkers {
    const val HEADING = "## Markers"

    /** Spoken text and the trailing markers section (null when there is none). */
    fun split(markdown: String): Pair<String, String?> {
        val start = if (markdown.startsWith("$HEADING\n")) 0 else markdown.lastIndexOf("\n$HEADING\n") + 1
        if (start == 0 && !markdown.startsWith("$HEADING\n")) return markdown to null
        val section = markdown.substring(start)
        if (!section.lines().drop(1).all { it.isBlank() || it.startsWith("- **") }) return markdown to null
        return markdown.substring(0, start).trimEnd() to section.trimEnd()
    }

    /** The transcript without its markers section; null stays null. */
    fun spoken(markdown: String?): String? = markdown?.let { split(it).first }
}
