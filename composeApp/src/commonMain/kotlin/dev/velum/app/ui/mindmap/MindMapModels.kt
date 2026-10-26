package dev.velum.app.ui.mindmap

/**
 * Simple mind-map derived from summary Markdown:
 * root → H2 headings → list items under each heading (until the next H2).
 */
data class MindMapNode(
    val label: String,
    val children: List<MindMapNode> = emptyList(),
)

fun buildMindMap(
    markdown: String,
    rootTitle: String,
): MindMapNode {
    val root = rootTitle.ifBlank { "Resumo" }
    val sections = mutableListOf<Pair<String, MutableList<String>>>()
    var current: MutableList<String>? = null

    for (raw in markdown.lineSequence()) {
        val line = raw.trimEnd()
        val trimmed = line.trim()
        when {
            trimmed.startsWith("## ") && !trimmed.startsWith("###") -> {
                val heading = trimmed.removePrefix("## ").trim().ifBlank { "…" }
                val items = mutableListOf<String>()
                sections += heading to items
                current = items
            }
            current != null && isListItem(trimmed) -> {
                current += stripListMarker(trimmed)
            }
        }
    }

    return MindMapNode(
        label = root,
        children = sections.map { (heading, items) ->
            MindMapNode(
                label = heading,
                children = items.map { MindMapNode(it) },
            )
        },
    )
}

private fun isListItem(line: String): Boolean {
    if (line.isEmpty()) return false
    if (line.startsWith("- ") || line.startsWith("* ") || line.startsWith("+ ")) return true
    val i = line.indexOfFirst { !it.isDigit() }
    return i > 0 && i < line.length && line[i] == '.' && i + 1 < line.length && line[i + 1] == ' '
}

private fun stripListMarker(line: String): String {
    val trimmed = line.trim()
    return when {
        trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ") ->
            trimmed.drop(2).trim()
        else -> {
            val i = trimmed.indexOf(". ")
            if (i > 0 && trimmed.take(i).all { it.isDigit() }) {
                trimmed.drop(i + 2).trim()
            } else {
                trimmed
            }
        }
    }
}
