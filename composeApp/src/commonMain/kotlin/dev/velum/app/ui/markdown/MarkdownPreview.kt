package dev.velum.app.ui.markdown

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import dev.velum.app.ui.theme.VelumInk
import dev.velum.app.ui.theme.VelumInkSecondary

/**
 * Shared Markdown preview for transcripts and notes (speakers as bold, headings, lists).
 */
@Composable
fun MarkdownPreview(
    markdown: String,
    modifier: Modifier = Modifier,
) {
    val content = markdown.ifBlank { "—" }
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
}
