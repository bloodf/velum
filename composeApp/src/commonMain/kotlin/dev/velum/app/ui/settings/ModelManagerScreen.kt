package dev.velum.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.velum.ai.local.LocalModelKind
import dev.velum.ai.local.LocalModelState
import dev.velum.ai.local.LocalModelStatus
import dev.velum.app.di.AppContainer
import dev.velum.app.i18n.LocalStrings
import dev.velum.app.i18n.ModelCatalogUi
import dev.velum.app.i18n.Strings
import dev.velum.app.ui.components.BlackButton
import dev.velum.app.ui.components.OutlineButton
import dev.velum.app.ui.components.ScreenTopBar
import dev.velum.app.ui.theme.VelumBg
import dev.velum.app.ui.theme.VelumInk
import dev.velum.app.ui.theme.VelumInkSecondary
import dev.velum.app.ui.theme.VelumSurface
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

@Composable
fun ModelManagerScreen(
    container: AppContainer,
    onBack: () -> Unit,
) {
    val strings = LocalStrings.current
    val states by container.localModelManager.states.collectAsState()
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().background(VelumBg)) {
        ScreenTopBar(strings.modelManager, onBack = onBack)
        Text(
            strings.modelManagerHint,
            color = VelumInkSecondary,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(states, key = { it.spec.id }) { state ->
                ModelCard(
                    state = state,
                    onDownload = {
                        scope.launch {
                            container.localModelManager.download(state.spec.id).collect()
                        }
                    },
                    onDelete = { container.localModelManager.delete(state.spec.id) },
                )
            }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

@Composable
private fun ModelCard(
    state: LocalModelState,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
) {
    val strings = LocalStrings.current
    val copy = ModelCatalogUi.copyFor(strings.localeTag, state.spec.id)
    val statusLabel = when (state.status) {
        LocalModelStatus.Ready -> strings.modelReady
        LocalModelStatus.Downloading -> strings.modelDownloading
        LocalModelStatus.Failed -> strings.modelFailed
        LocalModelStatus.NotDownloaded -> strings.modelNotDownloaded
        LocalModelStatus.BlockedOnUrl -> strings.modelBlockedOnUrl
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(VelumSurface)
            .padding(16.dp),
    ) {
        Text(
            state.spec.displayName,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
            color = VelumInk,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            kindLabel(state.spec.kind, strings),
            color = VelumInkSecondary,
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(6.dp))
        Text(copy.blurb, color = VelumInk, fontSize = 13.sp, lineHeight = 18.sp)
        Spacer(Modifier.height(6.dp))
        Text(
            "${strings.approxSize}: ${formatBytes(state.spec.approxBytes)}",
            color = VelumInkSecondary,
            fontSize = 12.sp,
        )
        if (copy.languages.isNotBlank()) {
            Text(copy.languages, color = VelumInkSecondary, fontSize = 12.sp)
        }
        Spacer(Modifier.height(8.dp))
        Text(statusLabel, fontWeight = FontWeight.Medium, fontSize = 13.sp, color = VelumInk)
        state.error?.let { err ->
            Spacer(Modifier.height(4.dp))
            Text(err, color = VelumInkSecondary, fontSize = 12.sp, lineHeight = 16.sp)
        }
        if (state.status == LocalModelStatus.Downloading) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { state.progress },
                modifier = Modifier.fillMaxWidth().height(4.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            when (state.status) {
                LocalModelStatus.Ready -> {
                    OutlineButton(strings.delete, onDelete, Modifier.weight(1f))
                }
                LocalModelStatus.Downloading -> {
                    Spacer(Modifier.weight(1f))
                }
                LocalModelStatus.BlockedOnUrl -> {
                    Spacer(Modifier.weight(1f))
                }
                LocalModelStatus.NotDownloaded, LocalModelStatus.Failed -> {
                    BlackButton(strings.download, onDownload, Modifier.weight(1f))
                    Spacer(Modifier.width(0.dp))
                }
            }
        }
    }
}

private fun kindLabel(kind: LocalModelKind, strings: Strings): String = when (kind) {
    LocalModelKind.Asr -> strings.ux.modelKindAsr
    LocalModelKind.Diarization -> strings.ux.modelKindDiarization
    LocalModelKind.Embedding -> strings.ux.modelKindEmbedding
    LocalModelKind.Llm -> strings.ux.modelKindLlm
}

private fun formatBytes(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024) {
        val gb = (mb / 1024.0 * 10).toInt() / 10.0
        "$gb GB"
    } else {
        "${mb.toInt()} MB"
    }
}
