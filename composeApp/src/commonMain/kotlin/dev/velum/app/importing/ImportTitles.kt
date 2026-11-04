package dev.velum.app.importing

/** Readable recording titles for imported files. */
object ImportTitles {
    const val MAX_TITLE_CHARS = 120
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
        displayName.filterNot { it.isISOControl() }.substringAfterLast('/').substringBeforeLast('.').trim().take(MAX_TITLE_CHARS).ifBlank { null }

    fun fromFileName(fileName: String): String? =
        fileName.substringBeforeLast('.')
            .replace(internalPrefix, "")
            .replace('_', ' ')
            .trim()
            .ifBlank { null }
}
