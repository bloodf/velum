package dev.velum.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.velum.app.di.AppContainer
import dev.velum.app.i18n.LocalStrings
import dev.velum.app.ui.components.ChevronRow
import dev.velum.app.ui.components.ScreenTopBar
import dev.velum.app.ui.components.SectionLabel
import dev.velum.app.ui.theme.VelumBg
import dev.velum.app.ui.theme.VelumBlack
import dev.velum.app.ui.theme.VelumDivider
import dev.velum.app.ui.theme.VelumInk
import dev.velum.app.ui.theme.VelumInkSecondary
import dev.velum.app.ui.theme.VelumSurface
import dev.velum.core.model.ComputeMode
import kotlinx.coroutines.launch

@Composable
fun AiProcessingScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onModelManager: () -> Unit,
) {
    val strings = LocalStrings.current
    val settings by container.settingsRepository.settings.collectAsState()
    val scope = rememberCoroutineScope()

    Column(
        Modifier
            .fillMaxSize()
            .background(VelumBg)
            .verticalScroll(rememberScrollState()),
    ) {
        ScreenTopBar(strings.aiProcessing, onBack = onBack)
        Text(
            strings.aiProcessingHint,
            color = VelumInkSecondary,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        Spacer(Modifier.height(12.dp))
        SectionLabel(strings.taskTranscription)
        ModePicker(
            selected = settings.transcriptionMode,
            onSelect = { mode -> scope.launch { container.settingsRepository.setTranscriptionMode(mode) } },
        )
        Spacer(Modifier.height(16.dp))
        SectionLabel(strings.taskDiarization)
        ModePicker(
            selected = settings.diarizationMode,
            onSelect = { mode -> scope.launch { container.settingsRepository.setDiarizationMode(mode) } },
        )
        Spacer(Modifier.height(16.dp))
        SectionLabel(strings.taskSummaries)
        ModePicker(
            selected = settings.summaryMode,
            onSelect = { mode -> scope.launch { container.settingsRepository.setSummaryMode(mode) } },
        )
        Spacer(Modifier.height(20.dp))
        ChevronRow(
            strings.audioLanguage,
            {},
            value = settings.audioLanguage,
        )
        ChevronRow(
            strings.outputLanguage,
            {},
            value = settings.outputLanguage,
            showDivider = false,
        )
        Spacer(Modifier.height(12.dp))
        ChevronRow(
            strings.modelManager,
            onModelManager,
            value = null,
            showDivider = false,
        )
        Text(
            strings.modelManagerHint,
            color = VelumInkSecondary,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun ModePicker(
    selected: ComputeMode,
    onSelect: (ComputeMode) -> Unit,
) {
    val strings = LocalStrings.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ModeChip(
            label = strings.computeLocal,
            selected = selected == ComputeMode.Local,
            onClick = { onSelect(ComputeMode.Local) },
            modifier = Modifier.weight(1f),
        )
        ModeChip(
            label = strings.computeApi,
            selected = selected == ComputeMode.Api,
            onClick = { onSelect(ComputeMode.Api) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ModeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .height(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) VelumBlack else VelumSurface)
            .border(1.dp, if (selected) VelumBlack else VelumDivider, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (selected) Color.White else VelumInk,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
        )
    }
}
