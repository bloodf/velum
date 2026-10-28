package dev.velum.app.export

/**
 * Pure text layout helpers for PDF export (wrap + paginate).
 * Keeps Android PdfDocument code free of non-trivial string logic.
 */
object PdfTextLayout {
    fun wrapLine(line: String, maxCharsPerLine: Int): List<String> {
        require(maxCharsPerLine > 0) { "maxCharsPerLine must be > 0" }
        if (line.isEmpty()) return listOf("")
        if (line.length <= maxCharsPerLine) return listOf(line)
        val out = ArrayList<String>()
        var remaining = line
        while (remaining.length > maxCharsPerLine) {
            val window = remaining.take(maxCharsPerLine + 1)
            val breakAt = window.lastIndexOf(' ').takeIf { it > 0 } ?: maxCharsPerLine
            out += remaining.take(breakAt).trimEnd()
            remaining = remaining.drop(breakAt).trimStart()
        }
        if (remaining.isNotEmpty()) out += remaining
        return out
    }

    fun wrapBody(body: String, maxCharsPerLine: Int): List<String> {
        if (body.isBlank()) return listOf(" ")
        return body.lineSequence().flatMap { wrapLine(it, maxCharsPerLine) }.toList()
            .ifEmpty { listOf(" ") }
    }

    fun paginate(lines: List<String>, linesPerPage: Int): List<List<String>> {
        require(linesPerPage > 0) { "linesPerPage must be > 0" }
        if (lines.isEmpty()) return listOf(listOf(" "))
        return lines.chunked(linesPerPage)
    }
}
