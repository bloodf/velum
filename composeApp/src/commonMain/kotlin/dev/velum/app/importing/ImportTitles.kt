package dev.velum.app.importing

/** Readable recording titles for imported files. */
object ImportTitles {
    const val MAX_TITLE_CHARS = 120

    /** Zero-width, bidi-override and BOM characters other apps could use to disguise a name. */
    private val invisible = setOf('\u200B', '\u200C', '\u200D', '\u200E', '\u200F', '\uFEFF') + ('\u202A'..'\u202E') + ('\u2066'..'\u2069')

    /** A provider-supplied name without control characters and invisible formatting characters. */
    fun cleanDisplayName(name: String): String = name.filterNot { it.isISOControl() || it in invisible }
    private val internalPrefix = Regex("""^(?:share_)?\d{9,}_""")

    /**
     * Title from a cached import file name: no extension, no internal `<millis>_` / `share_<millis>_`
     * prefix (added to keep cache names unique), underscores read as spaces. Null when nothing is left.
     */
    /**
     * Title for an imported file: the provider's display name when one was recorded for this cached path
     * (spaces, parentheses and a leading date survive), else [fromFileName] of the cache name.
     */
    fun forImport(cachedPath: String, cachedFileName: String): String? =
        ImportDisplayNames.take(cachedPath)?.let { fromDisplayName(it) } ?: fromFileName(cachedFileName)

    /** The name the user sees in the file picker, without its extension. */
    fun fromDisplayName(displayName: String): String? =
        cleanDisplayName(displayName).substringAfterLast('/').substringBeforeLast('.').trim().take(MAX_TITLE_CHARS).ifBlank { null }

    fun fromFileName(fileName: String): String? =
        fileName.substringBeforeLast('.')
            .replace(internalPrefix, "")
            .replace('_', ' ')
            .trim()
            .ifBlank { null }
}
