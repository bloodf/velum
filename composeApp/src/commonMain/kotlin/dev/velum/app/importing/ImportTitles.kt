package dev.velum.app.importing

/** Readable recording titles for imported files. */
object ImportTitles {
    private val internalPrefix = Regex("""^(?:share_)?\d{9,}_""")

    /**
     * Title from a cached import file name: no extension, no internal `<millis>_` / `share_<millis>_`
     * prefix (added to keep cache names unique), underscores read as spaces. Null when nothing is left.
     */
    fun fromFileName(fileName: String): String? =
        fileName.substringBeforeLast('.')
            .replace(internalPrefix, "")
            .replace('_', ' ')
            .trim()
            .ifBlank { null }
}
