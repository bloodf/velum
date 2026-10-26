package dev.velum.app.ui.markdown

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import androidx.compose.material3.MaterialTheme
import dev.velum.app.ui.theme.VelumInk
import dev.velum.app.ui.theme.VelumInkSecondary

/**
 * Markdown preview for transcripts and notes (speakers as bold, timestamps,
 * headings, lists). Uses a deterministic Compose block renderer so content is
 * measurable under Robolectric/Roborazzi; mikepenz is retained for richer
 * in-app rendering when [preferRichRenderer] is true (device / emulator).
 */
@Composable
fun MarkdownPreview(
    markdown: String,
    modifier: Modifier = Modifier,
    preferRichRenderer: Boolean = false,
) {
    val content = markdown.ifBlank { "—" }
    if (preferRichRenderer) {
        Markdown(
            content = content,
            modifier = modifier.fillMaxWidth(),
            colors = markdownColor(
                text = VelumInk,
                codeBackground = VelumInkSecondary.copy(alpha = 0.08f),
            ),
            typography = markdownTypography(
                h1 = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 22.sp,
                    color = VelumInk,
                ),
                h2 = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp,
                    color = VelumInk,
                ),
                h3 = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    color = VelumInk,
                ),
                text = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                    color = VelumInk,
                ),
                paragraph = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                    color = VelumInk,
                ),
                bullet = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                    color = VelumInk,
                ),
                ordered = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                    color = VelumInk,
                ),
            ),
        )
    } else {
        ComposeMarkdownDocument(content, modifier.fillMaxWidth())
    }
}

@Composable
internal fun ComposeMarkdownDocument(
    markdown: String,
    modifier: Modifier = Modifier,
) {
    val blocks = remember(markdown) { parseMarkdownBlocks(markdown) }
    Column(modifier) {
        blocks.forEachIndexed { index, block ->
            when (block) {
                is MdBlock.Heading -> {
                    Text(
                        text = inlineMarkdown(block.text),
                        color = VelumInk,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = when (block.level) {
                            1 -> 22.sp
                            2 -> 18.sp
                            else -> 16.sp
                        },
                        lineHeight = when (block.level) {
                            1 -> 28.sp
                            2 -> 24.sp
                            else -> 22.sp
                        },
                        modifier = Modifier.padding(top = if (index == 0) 0.dp else 12.dp, bottom = 4.dp),
                    )
                }
                is MdBlock.Bullet -> {
                    Row(Modifier.padding(vertical = 2.dp)) {
                        Text("•", color = VelumInk, fontSize = 15.sp, modifier = Modifier.width(16.dp))
                        Text(
                            text = inlineMarkdown(block.text),
                            color = VelumInk,
                            fontSize = 15.sp,
                            lineHeight = 22.sp,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                is MdBlock.Ordered -> {
                    Row(Modifier.padding(vertical = 2.dp)) {
                        Text(
                            "${block.index}.",
                            color = VelumInk,
                            fontSize = 15.sp,
                            modifier = Modifier.width(22.dp),
                        )
                        Text(
                            text = inlineMarkdown(block.text),
                            color = VelumInk,
                            fontSize = 15.sp,
                            lineHeight = 22.sp,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                is MdBlock.Paragraph -> {
                    Text(
                        text = inlineMarkdown(block.text),
                        color = VelumInk,
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                        modifier = Modifier.padding(vertical = 3.dp),
                    )
                }
                MdBlock.Spacer -> Spacer(Modifier.height(8.dp))
            }
        }
    }
}

internal sealed class MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock()
    data class Bullet(val text: String) : MdBlock()
    data class Ordered(val index: Int, val text: String) : MdBlock()
    data class Paragraph(val text: String) : MdBlock()
    data object Spacer : MdBlock()
}

internal fun parseMarkdownBlocks(markdown: String): List<MdBlock> {
    val out = mutableListOf<MdBlock>()
    val lines = markdown.replace("\r\n", "\n").lines()
    var i = 0
    while (i < lines.size) {
        val raw = lines[i]
        val trimmed = raw.trim()
        when {
            trimmed.isEmpty() -> {
                if (out.isNotEmpty() && out.last() !is MdBlock.Spacer) out += MdBlock.Spacer
                i++
            }
            trimmed.startsWith("### ") -> {
                out += MdBlock.Heading(3, trimmed.removePrefix("### ").trim())
                i++
            }
            trimmed.startsWith("## ") -> {
                out += MdBlock.Heading(2, trimmed.removePrefix("## ").trim())
                i++
            }
            trimmed.startsWith("# ") -> {
                out += MdBlock.Heading(1, trimmed.removePrefix("# ").trim())
                i++
            }
            trimmed.startsWith("- [ ]") || trimmed.startsWith("- [x]") || trimmed.startsWith("- [X]") -> {
                val text = trimmed
                    .removePrefix("- [ ] ")
                    .removePrefix("- [x] ")
                    .removePrefix("- [X] ")
                    .removePrefix("- [ ]")
                    .removePrefix("- [x]")
                    .removePrefix("- [X]")
                    .trim()
                out += MdBlock.Bullet(if (text.isEmpty()) "☐" else "☐ $text")
                i++
            }
            trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ") -> {
                out += MdBlock.Bullet(trimmed.drop(2).trim())
                i++
            }
            trimmed.matches(Regex("""^\d+\.\s+.*""")) -> {
                val dot = trimmed.indexOf(". ")
                val n = trimmed.take(dot).toIntOrNull() ?: 1
                out += MdBlock.Ordered(n, trimmed.drop(dot + 2).trim())
                i++
            }
            else -> {
                val buf = StringBuilder(trimmed)
                i++
                while (i < lines.size) {
                    val next = lines[i].trim()
                    if (next.isEmpty() || next.startsWith("#") || next.startsWith("- ") ||
                        next.startsWith("* ") || next.startsWith("+ ") ||
                        next.matches(Regex("""^\d+\.\s+.*"""))
                    ) {
                        break
                    }
                    buf.append(' ').append(next)
                    i++
                }
                out += MdBlock.Paragraph(buf.toString())
            }
        }
    }
    return out.ifEmpty { listOf(MdBlock.Paragraph("—")) }
}

/** Supports `**bold**` and nested plain text (speaker labels, timestamps). */
internal fun inlineMarkdown(text: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        if (text.startsWith("**", i)) {
            val end = text.indexOf("**", i + 2)
            if (end > i) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = VelumInk)) {
                    append(text.substring(i + 2, end))
                }
                i = end + 2
                continue
            }
        }
        append(text[i])
        i++
    }
}
