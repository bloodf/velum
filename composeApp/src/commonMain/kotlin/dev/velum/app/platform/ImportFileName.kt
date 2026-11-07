package dev.velum.app.platform

private const val MAX_NAME = 120
private const val MAX_EXTENSION = 10

/**
 * A safe file name for an imported document: no directories, no `..`, only letters, digits, `.`, `_`, `-`.
 * Long names are shortened before the extension so the extension survives.
 */
fun sanitizeImportFileName(raw: String?, fallback: String = "import.audio"): String {
    val cleaned = raw
        ?.substringAfterLast('/')
        ?.substringAfterLast('\\')
        ?.trim()
        .orEmpty()
        .replace(' ', '_') // title derivation turns underscores back into spaces
        .replace("..", "")
        .filter { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }
    if (cleaned.isBlank()) return fallback
    if (cleaned.length <= MAX_NAME) return cleaned
    val extension = cleaned.substringAfterLast('.', "").takeIf { it.length in 1..MAX_EXTENSION }
    return if (extension == null) cleaned.take(MAX_NAME)
    else cleaned.removeSuffix(".$extension").take(MAX_NAME - extension.length - 1) + "." + extension
}
