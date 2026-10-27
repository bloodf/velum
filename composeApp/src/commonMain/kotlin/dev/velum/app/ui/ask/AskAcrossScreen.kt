package dev.velum.app.ui.ask

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.velum.ai.pipeline.AskAcrossRecordings
import dev.velum.app.di.AppContainer
import dev.velum.app.i18n.LocalStrings
import dev.velum.app.ui.components.BlackButton
import dev.velum.app.ui.markdown.MarkdownPreview
import dev.velum.app.ui.components.ScreenTopBar
import dev.velum.app.ui.theme.VelumBg
import dev.velum.app.ui.theme.VelumDivider
import dev.velum.app.ui.theme.VelumInk
import dev.velum.app.ui.theme.VelumInkSecondary
import dev.velum.app.ui.theme.VelumSurface
import kotlinx.coroutines.launch

@Composable
fun AskAcrossScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onOpenCitation: (recordingId: String, offsetSeconds: Long) -> Unit,
    /** Optional pre-filled Q&A for marketing / screenshot fixtures. */
    previewAnswer: AskAcrossRecordings.Answer? = null,
    initialQuestion: String = "",
) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    var question by remember { mutableStateOf(initialQuestion) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var answer by remember { mutableStateOf(previewAnswer) }

    Column(
        Modifier
            .fillMaxSize()
            .background(VelumBg)
            .verticalScroll(rememberScrollState()),
    ) {
        ScreenTopBar(strings.ux.askAcrossTitle, onBack = onBack)
        Column(Modifier.padding(20.dp)) {
            Text(strings.ux.askAcrossHint, color = VelumInkSecondary, fontSize = 14.sp, lineHeight = 20.sp)
            Spacer(Modifier.height(16.dp))
            BasicTextField(
                value = question,
                onValueChange = { question = it },
                textStyle = TextStyle(color = VelumInk, fontSize = 16.sp, lineHeight = 22.sp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .border(1.dp, VelumDivider, RoundedCornerShape(12.dp))
                    .padding(14.dp),
                decorationBox = { inner ->
                    if (question.isEmpty()) {
                        Text(strings.ux.askAcrossPlaceholder, color = VelumInkSecondary, fontSize = 16.sp)
                    }
                    inner()
                },
            )
            Spacer(Modifier.height(12.dp))
            BlackButton(
                if (busy) strings.generating else strings.askAiSend,
                {
                    if (busy || question.isBlank()) return@BlackButton
                    busy = true
                    error = null
                    scope.launch {
                        runCatching { container.askAcross.ask(question) }
                            .onSuccess { answer = it }
                            .onFailure { error = it.message ?: it::class.simpleName }
                        busy = false
                    }
                },
                enabled = question.isNotBlank() && !busy,
            )
            error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = VelumInk, fontSize = 14.sp)
            }
            val current = answer
            if (current == null) {
                Spacer(Modifier.height(24.dp))
                Text(strings.ux.askAcrossEmpty, color = VelumInkSecondary, fontSize = 14.sp)
            } else {
                Spacer(Modifier.height(24.dp))
                Text("Answer", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = VelumInk)
                Spacer(Modifier.height(8.dp))
                MarkdownPreview(current.markdown)
                if (current.citations.isNotEmpty()) {
                    Spacer(Modifier.height(20.dp))
                    Text(strings.ux.askAcrossSources, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Spacer(Modifier.height(8.dp))
                    current.citations.forEachIndexed { index, citation ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(VelumSurface)
                                .clickable { onOpenCitation(citation.recordingId, citation.offsetSeconds) }
                                .padding(14.dp),
                        ) {
                            Text(
                                "[${index + 1}] ${citation.title} · ${citation.timestampLabel}",
                                fontWeight = FontWeight.Medium,
                                color = VelumInk,
                                fontSize = 14.sp,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                citation.snippet,
                                color = VelumInkSecondary,
                                fontSize = 13.sp,
                                lineHeight = 18.sp,
                                maxLines = 4,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}
