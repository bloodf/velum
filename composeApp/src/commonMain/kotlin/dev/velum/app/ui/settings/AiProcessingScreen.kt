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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.velum.ai.engine.AiTask
import dev.velum.ai.engine.ModeResolution
import dev.velum.app.di.AppContainer
import dev.velum.app.i18n.LocalStrings
import dev.velum.app.ui.components.BlackButton
import dev.velum.app.ui.components.ChevronRow
import dev.velum.app.ui.components.LocalModelMissingDialog
import dev.velum.app.ui.components.OutlineButton
import dev.velum.app.ui.components.ScreenTopBar
import dev.velum.app.ui.components.SectionLabel
import dev.velum.app.ui.theme.VelumBg
import dev.velum.app.ui.theme.VelumBlack
import dev.velum.app.ui.theme.VelumDivider
import dev.velum.app.ui.theme.VelumInk
import dev.velum.app.ui.theme.VelumInkSecondary
import dev.velum.app.ui.theme.VelumOnBlack
import dev.velum.app.ui.theme.VelumOnWarningContainer
import dev.velum.app.ui.theme.VelumSurface
import dev.velum.app.ui.theme.VelumWarningContainer
import dev.velum.core.model.ComputeMode
import kotlinx.coroutines.launch

private data class LangOption(val code: String, val label: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiProcessingScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onModelManager: () -> Unit,
) {
    val strings = LocalStrings.current
    val settings by container.settingsRepository.settings.collectAsState()
    val modelStates by container.localModelManager.states.collectAsState()
    val scope = rememberCoroutineScope()
    var pickAudio by remember { mutableStateOf(false) }
    var pickOutput by remember { mutableStateOf(false) }
    var modelPrompt by remember { mutableStateOf<ModeResolution.NeedLocalModel?>(null) }

    val languages = listOf(
        LangOption("auto", strings.languageAuto),
        LangOption("pt-BR", strings.languagePtBr),
        LangOption("en", strings.languageEn),
    )

    fun labelFor(code: String): String =
        languages.firstOrNull { it.code == code }?.label ?: code

    val missingLocal = remember(settings, modelStates) {
        listOf(AiTask.Transcription, AiTask.Diarization, AiTask.Summary).mapNotNull { task ->
            when (val r = container.aiRouter.resolveModeOrPrompt(task, container.localModelManager)) {
                is ModeResolution.NeedLocalModel -> r
                is ModeResolution.Proceed -> null
            }
        }
    }

    // Pin ScreenTopBar outside the scroll so Back stays reachable (#118).
    Column(
        Modifier
            .fillMaxSize()
            .background(VelumBg),
    ) {
        ScreenTopBar(strings.aiProcessing, onBack = onBack)
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                strings.aiProcessingHint,
                color = VelumInkSecondary,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            if (missingLocal.isNotEmpty()) {
                val first = missingLocal.first()
                Column(
                    Modifier
                        .padding(horizontal = 20.dp, vertical = 8.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(VelumWarningContainer)
                        .padding(12.dp),
                ) {
                    Text(
                        strings.localModelMissingTitle,
                        fontWeight = FontWeight.SemiBold,
                        color = VelumOnWarningContainer,
                        fontSize = 14.sp,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        strings.localModelMissingBody.replace("%s", first.displayName),
                        color = VelumOnWarningContainer,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BlackButton(
                            strings.downloadModel,
                            onModelManager,
                            Modifier.weight(1f),
                        )
                        OutlineButton(
                            strings.switchToApi,
                            {
                                scope.launch {
                                    container.settingsRepository.setTranscriptionMode(ComputeMode.Api)
                                    // Diarization has no remote contract — stay Local.
                                    container.settingsRepository.setSummaryMode(ComputeMode.Api)
                                }
                            },
                            Modifier.weight(1f),
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            SectionLabel(strings.taskTranscription)
            ModePicker(
                selected = settings.transcriptionMode,
                onSelect = { mode ->
                    if (mode == ComputeMode.Local) {
                        when (
                            val r = container.aiRouter.resolveModeOrPrompt(
                                AiTask.Transcription,
                                container.localModelManager,
                            )
                        ) {
                            is ModeResolution.NeedLocalModel -> {
                                modelPrompt = r
                                return@ModePicker
                            }
                            is ModeResolution.Proceed -> Unit
                        }
                    }
                    scope.launch { container.settingsRepository.setTranscriptionMode(mode) }
                },
            )
            Spacer(Modifier.height(16.dp))
            SectionLabel(strings.taskDiarization)
            // API diarization has no remote contract — Local only.
            LaunchedEffect(settings.diarizationMode) {
                if (settings.diarizationMode == ComputeMode.Api) {
                    container.settingsRepository.setDiarizationMode(ComputeMode.Local)
                }
            }
            ModePicker(
                selected = ComputeMode.Local,
                onSelect = { mode ->
                    if (mode != ComputeMode.Local) return@ModePicker
                    when (
                        val r = container.aiRouter.resolveModeOrPrompt(
                            AiTask.Diarization,
                            container.localModelManager,
                        )
                    ) {
                        is ModeResolution.NeedLocalModel -> {
                            modelPrompt = r
                            return@ModePicker
                        }
                        is ModeResolution.Proceed -> Unit
                    }
                    scope.launch { container.settingsRepository.setDiarizationMode(ComputeMode.Local) }
                },
                allowApi = false,
            )
            Spacer(Modifier.height(16.dp))
            SectionLabel(strings.taskSummaries)
            ModePicker(
                selected = settings.summaryMode,
                onSelect = { mode ->
                    if (mode == ComputeMode.Local) {
                        when (
                            val r = container.aiRouter.resolveModeOrPrompt(
                                AiTask.Summary,
                                container.localModelManager,
                            )
                        ) {
                            is ModeResolution.NeedLocalModel -> {
                                modelPrompt = r
                                return@ModePicker
                            }
                            is ModeResolution.Proceed -> Unit
                        }
                    }
                    scope.launch { container.settingsRepository.setSummaryMode(mode) }
                },
            )
            Spacer(Modifier.height(20.dp))
            SectionLabel(strings.ux.asrSizeSection)
            Text(
                strings.ux.asrSizeHint,
                color = VelumInkSecondary,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            AsrTierPicker(
                selected = settings.asrSizeTier,
                onSelect = { tier ->
                    scope.launch { container.settingsRepository.setAsrSizeTier(tier) }
                },
            )
            Spacer(Modifier.height(16.dp))
            ChevronRow(
                strings.audioLanguage,
                { pickAudio = true },
                value = labelFor(settings.audioLanguage),
            )
            ChevronRow(
                strings.outputLanguage,
                { pickOutput = true },
                value = labelFor(settings.outputLanguage),
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

    if (pickAudio) {
        LanguagePickerSheet(
            title = strings.audioLanguage,
            options = languages,
            selected = settings.audioLanguage,
            onSelect = { code ->
                scope.launch { container.settingsRepository.setAudioLanguage(code) }
                pickAudio = false
            },
            onDismiss = { pickAudio = false },
        )
    }
    if (pickOutput) {
        LanguagePickerSheet(
            title = strings.outputLanguage,
            options = languages,
            selected = settings.outputLanguage,
            onSelect = { code ->
                scope.launch { container.settingsRepository.setOutputLanguage(code) }
                pickOutput = false
            },
            onDismiss = { pickOutput = false },
        )
    }
    modelPrompt?.let { need ->
        LocalModelMissingDialog(
            modelDisplayName = need.displayName,
            onDownload = {
                modelPrompt = null
                onModelManager()
            },
            onSwitchToApi = {
                scope.launch {
                    when (need.task) {
                        AiTask.Transcription ->
                            container.settingsRepository.setTranscriptionMode(ComputeMode.Api)
                        AiTask.Diarization -> {
                            // No API diarization — keep prompting for local download.
                            modelPrompt = need
                            return@launch
                        }
                        AiTask.Summary ->
                            container.settingsRepository.setSummaryMode(ComputeMode.Api)
                    }
                    modelPrompt = null
                }
            },
            onDismiss = { modelPrompt = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguagePickerSheet(
    title: String,
    options: List<LangOption>,
    selected: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = VelumSurface,
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = VelumInk)
            Spacer(Modifier.height(12.dp))
            options.forEach { opt ->
                Text(
                    opt.label,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(opt.code) }
                        .padding(vertical = 14.dp),
                    color = if (opt.code == selected) VelumInk else VelumInkSecondary,
                    fontWeight = if (opt.code == selected) FontWeight.SemiBold else FontWeight.Normal,
                    fontSize = 16.sp,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ModePicker(
    selected: ComputeMode,
    onSelect: (ComputeMode) -> Unit,
    allowApi: Boolean = true,
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
        if (allowApi) {
            ModeChip(
                label = strings.computeApi,
                selected = selected == ComputeMode.Api,
                onClick = { onSelect(ComputeMode.Api) },
                modifier = Modifier.weight(1f),
            )
        }
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
            color = if (selected) VelumOnBlack else VelumInk,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
        )
    }
}

@Composable
private fun AsrTierPicker(
    selected: String,
    onSelect: (String) -> Unit,
) {
    val strings = LocalStrings.current
    val options = listOf(
        "auto" to strings.ux.asrTierAuto,
        "tiny" to strings.ux.asrTierTiny,
        "standard" to strings.ux.asrTierStandard,
        "quality" to strings.ux.asrTierQuality,
    )
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (id, label) ->
            ModeChip(
                label = label,
                selected = selected.equals(id, ignoreCase = true),
                onClick = { onSelect(id) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}
