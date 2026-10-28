package dev.velum.app.ui.recording

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
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.velum.ai.engine.AiTask
import dev.velum.ai.engine.FakeStreamingTranscriber
import dev.velum.ai.engine.ModeResolution
import dev.velum.ai.engine.StreamingTranscriber
import dev.velum.ai.engine.createStreamingTranscriber
import dev.velum.ai.local.LocalAsrRouter
import dev.velum.ai.local.LocalModelStatus
import dev.velum.app.di.AppContainer
import dev.velum.app.i18n.LocalStrings
import dev.velum.app.platform.MicInputSource
import dev.velum.app.platform.MicMarkerResult
import dev.velum.app.platform.MicStartResult
import dev.velum.app.platform.MicStopResult
import dev.velum.app.platform.WavWriter
import dev.velum.app.platform.recordingsDirectoryPath
import dev.velum.app.platform.shareText
import dev.velum.app.ui.components.AiGlowFooter
import dev.velum.app.ui.components.BlackButton
import dev.velum.app.ui.components.ChevronRow
import dev.velum.app.ui.components.DualActionRow
import dev.velum.app.ui.components.LocalModelMissingDialog
import dev.velum.app.ui.components.OutlineButton
import dev.velum.app.ui.components.ThinDivider
import dev.velum.app.ui.markdown.MarkdownPreview
import dev.velum.app.ui.mindmap.MindMapView
import dev.velum.app.ui.mindmap.buildMindMap
import dev.velum.app.ui.theme.VelumBg
import dev.velum.app.ui.theme.VelumBlack
import dev.velum.app.ui.theme.VelumDivider
import dev.velum.app.ui.theme.VelumInk
import dev.velum.app.ui.theme.VelumInkSecondary
import dev.velum.app.ui.theme.VelumOnBlack
import dev.velum.app.ui.theme.VelumSurface
import dev.velum.core.model.ComputeMode
import dev.velum.core.templates.BuiltinTemplateCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingDetailScreen(
    container: AppContainer,
    recordingId: String,
    onBack: () -> Unit,
    onOpenModelManager: () -> Unit = {},
    initialTab: Int = 0,
    showSpeakersInitially: Boolean = false,
    showGenerateInitially: Boolean = false,
    /** Playback seek position from Ask citations (seconds). */
    initialSeekSeconds: Long = 0,
) {
    val strings = LocalStrings.current
    val recording by container.repository.observeRecording(recordingId).collectAsState(null)
    val folders by container.repository.observeFolders().collectAsState(emptyList())
    var tab by remember { mutableStateOf(initialTab) } // 0 fontes, 1 notas
    var seekSeconds by remember(recordingId, initialSeekSeconds) {
        mutableStateOf(initialSeekSeconds.coerceAtLeast(0))
    }
    var showMenu by remember { mutableStateOf(false) }
    var showSpeakers by remember { mutableStateOf(showSpeakersInitially) }
    var showGenerate by remember { mutableStateOf(showGenerateInitially) }
    var showMove by remember { mutableStateOf(false) }
    var showFindReplace by remember { mutableStateOf(false) }
    var showAskAi by remember { mutableStateOf(false) }
    var showMindMap by remember { mutableStateOf(false) }
    var askDraft by remember { mutableStateOf("") }
    var askAnswer by remember { mutableStateOf<String?>(null) }
    var askBusy by remember { mutableStateOf(false) }
    var askError by remember { mutableStateOf<String?>(null) }
    var processError by remember { mutableStateOf<String?>(null) }
    var pendingRetranscribe by remember { mutableStateOf<Boolean?>(null) }
    var modelPrompt by remember { mutableStateOf<ModeResolution.NeedLocalModel?>(null) }
    val scope = rememberCoroutineScope()
    val isLiveRecording = recording?.status == "recording"
    val pausedThermal = recording?.status == "paused_thermal"
    val generating = !isLiveRecording && (
        recording?.status == "generating" ||
            pausedThermal ||
            (recording?.summary_markdown.isNullOrBlank() &&
                recording?.status != "failed" &&
                recording?.status != "ready" &&
                recording?.status != "queued")
        )

    fun enqueueAfterGate(retranscribe: Boolean, templateId: String? = "velum.meeting") {
        processError = null
        val tasks = listOf(AiTask.Transcription, AiTask.Diarization, AiTask.Summary)
        for (task in tasks) {
            when (val resolution = container.aiRouter.resolveModeOrPrompt(task, container.localModelManager)) {
                is ModeResolution.NeedLocalModel -> {
                    pendingRetranscribe = retranscribe
                    modelPrompt = resolution
                    return
                }
                is ModeResolution.Proceed -> Unit
            }
        }
        container.enqueueProcessing(
            recordingId = recordingId,
            templateId = templateId,
            retranscribe = retranscribe,
        )
    }

    fun launchProcessing(retranscribe: Boolean, templateId: String? = "velum.meeting") =
        enqueueAfterGate(retranscribe, templateId)

    fun exportMarkdown() {
        val rec = recording ?: return
        val md = buildString {
            append("# ").append(rec.title).append('\n').append('\n')
            append("## ").append(strings.transcription).append('\n').append('\n')
            append(rec.transcript.orEmpty().ifBlank { "—" }).append('\n').append('\n')
            append("## ").append(strings.summary).append('\n').append('\n')
            append(rec.summary_markdown.orEmpty().ifBlank { "—" }).append('\n')
        }
        shareText(rec.title, md)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(VelumSurface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .defaultMinSize(48.dp, 48.dp)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = strings.ux.back,
                    tint = VelumInk,
                    modifier = Modifier.size(24.dp),
                )
            }
            Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.Center) {
                TabLabel(strings.sources, selected = tab == 0) { tab = 0 }
                Spacer(Modifier.width(28.dp))
                TabLabel(strings.notes, selected = tab == 1) { tab = 1 }
            }
            Box(
                Modifier
                    .defaultMinSize(48.dp, 48.dp)
                    .clickable { exportMarkdown() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.IosShare,
                    contentDescription = strings.share,
                    tint = VelumInk,
                    modifier = Modifier.size(22.dp),
                )
            }
            Box {
                Box(
                    Modifier
                        .defaultMinSize(48.dp, 48.dp)
                        .clickable { showMenu = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.MoreHoriz,
                        contentDescription = strings.more,
                        tint = VelumInk,
                        modifier = Modifier.size(22.dp),
                    )
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem({ Text(strings.moveToFolder) }, {
                        showMenu = false
                        showMove = true
                    })
                    DropdownMenuItem({ Text(strings.findReplace) }, {
                        showMenu = false
                        showFindReplace = true
                    })
                    DropdownMenuItem({ Text(strings.retranscribe) }, {
                        showMenu = false
                        launchProcessing(retranscribe = true)
                    })
                    DropdownMenuItem({ Text(strings.nameSpeakers) }, {
                        showMenu = false
                        showSpeakers = true
                    })
                    DropdownMenuItem({ Text(strings.moveToTrash) }, {
                        showMenu = false
                        scope.launch {
                            container.repository.trashRecording(recordingId)
                            onBack()
                        }
                    })
                }
            }
            Spacer(Modifier.width(8.dp))
        }

        if (pausedThermal) {
            Text(
                strings.pausedThermal,
                color = Color(0xFFB45309),
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }

        if (isLiveRecording) {
            LiveTranscriptPane(
                container = container,
                recordingId = recordingId,
                title = recording?.title.orEmpty(),
                initialTranscript = recording?.transcript,
                initialElapsedSeconds = recording?.duration_seconds ?: 0L,
                onStoppedAndQueued = { /* status observed via Flow */ },
            )
        } else if (tab == 0) {
            SourcesPane(
                transcript = recording?.transcript,
                durationSeconds = recording?.duration_seconds ?: 0,
                positionSeconds = seekSeconds,
                hasLocalAudio = !recording?.local_path.isNullOrBlank(),
                generating = generating && recording?.transcript.isNullOrBlank(),
                transportMessage = processError?.takeIf { it.startsWith("playback:") }?.removePrefix("playback:"),
                onGenerate = { showGenerate = true },
                onPlaybackUnavailable = {
                    processError = "playback:" + strings.ux.playbackUnavailable
                },
            )
        } else {
            NotesPane(
                summary = recording?.summary_markdown,
                generating = generating,
                processError = processError,
                onAskAi = { showAskAi = true },
                onGenerate = { showGenerate = true },
                onMindMap = { showMindMap = true },
            )
        }
    }

    if (showMindMap) {
        val title = recording?.title.orEmpty().ifBlank { strings.summary }
        val map = remember(recording?.summary_markdown, title) {
            buildMindMap(recording?.summary_markdown.orEmpty(), title)
        }
        ModalBottomSheet(
            onDismissRequest = { showMindMap = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = VelumSurface,
        ) {
            Column(Modifier.fillMaxWidth().height(520.dp)) {
                Text(
                    strings.mindMap,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp,
                    color = VelumInk,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
                MindMapView(map, Modifier.weight(1f))
            }
        }
    }

    if (showAskAi) {
        ModalBottomSheet(
            onDismissRequest = { showAskAi = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = VelumSurface,
        ) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text(strings.askAi, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = VelumInk)
                Spacer(Modifier.height(6.dp))
                Text(strings.askAiHint, color = VelumInkSecondary, fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))
                BasicTextField(
                    value = askDraft,
                    onValueChange = { askDraft = it },
                    textStyle = TextStyle(color = VelumInk, fontSize = 15.sp),
                    cursorBrush = SolidColor(VelumInk),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp)
                        .border(1.dp, VelumDivider, RoundedCornerShape(10.dp))
                        .padding(12.dp),
                    decorationBox = { inner ->
                        if (askDraft.isEmpty()) {
                            Text(strings.askAiPlaceholder, color = VelumInkSecondary)
                        }
                        inner()
                    },
                )
                Spacer(Modifier.height(12.dp))
                BlackButton(
                    if (askBusy) "…" else strings.askAiSend,
                    {
                        scope.launch {
                            askBusy = true
                            askError = null
                            try {
                                askAnswer = container.askAiChat.ask(recordingId, askDraft)
                            } catch (t: Throwable) {
                                askError = t.message ?: t.toString()
                            } finally {
                                askBusy = false
                            }
                        }
                    },
                    enabled = askDraft.isNotBlank() && !askBusy,
                )
                askError?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = Color(0xFFFF3B30), fontSize = 13.sp)
                }
                askAnswer?.let { answer ->
                    Spacer(Modifier.height(12.dp))
                    MarkdownPreview(answer)
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (showSpeakers) {
        ModalBottomSheet(
            onDismissRequest = { showSpeakers = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = VelumSurface,
        ) {
            val speakers by container.repository.observeSpeakers(recordingId).collectAsState(emptyList())
            SpeakerLabelSheetContent(
                speakers = speakers.map {
                    SpeakerLabelRow(
                        id = it.id,
                        label = it.label,
                        displayName = it.display_name,
                        talkSeconds = it.talk_seconds,
                    )
                },
                onClose = { showSpeakers = false },
                onConfirm = { renames ->
                    scope.launch {
                        renames.forEach { (speakerId, name) ->
                            val trimmed = name.trim()
                            if (trimmed.isEmpty()) return@forEach
                            val row = speakers.firstOrNull { it.id == speakerId } ?: return@forEach
                            val emb = container.voiceProfileStore.cachedClusterEmbedding(
                                recordingId,
                                row.label,
                            )
                            val existing = container.voiceProfileStore.listProfiles()
                                .firstOrNull { it.displayName.equals(trimmed, ignoreCase = true) }
                            when {
                                existing != null && emb != null ->
                                    container.voiceProfileStore.refine(existing.id, emb)
                                else ->
                                    container.voiceProfileStore.promoteCached(
                                        recordingId = recordingId,
                                        speakerId = row.label,
                                        displayName = trimmed,
                                    )
                            }
                            container.repository.renameSpeaker(recordingId, speakerId, trimmed)
                        }
                        showSpeakers = false
                    }
                },
            )
        }
    }
    if (showGenerate) {
        ModalBottomSheet(
            onDismissRequest = { showGenerate = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = VelumSurface,
        ) {
            GenerateSheetContent(
                onClose = { showGenerate = false },
                onApply = { templateId, language, autoLabel ->
                    showGenerate = false
                    scope.launch {
                        if (language != "auto") {
                            container.settingsRepository.setAudioLanguage(language)
                        }
                        container.settingsRepository.setAutoSpeakerLabel(autoLabel)
                        launchProcessing(retranscribe = true, templateId = templateId)
                    }
                },
            )
        }
    }
    modelPrompt?.let { need ->
        LocalModelMissingDialog(
            modelDisplayName = need.displayName,
            onDownload = {
                modelPrompt = null
                onOpenModelManager()
            },
            onSwitchToApi = {
                scope.launch {
                    when (need.task) {
                        AiTask.Transcription ->
                            container.settingsRepository.setTranscriptionMode(ComputeMode.Api)
                        AiTask.Diarization -> {
                            // No API diarization contract — keep Local and open model manager.
                            modelPrompt = need
                            return@launch
                        }
                        AiTask.Summary ->
                            container.settingsRepository.setSummaryMode(ComputeMode.Api)
                    }
                    modelPrompt = null
                    val retranscribe = pendingRetranscribe ?: true
                    pendingRetranscribe = null
                    enqueueAfterGate(retranscribe)
                }
            },
            onDismiss = {
                modelPrompt = null
                pendingRetranscribe = null
            },
        )
    }
    if (showMove) {
        ModalBottomSheet(
            onDismissRequest = { showMove = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = VelumSurface,
        ) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text(strings.moveToFolder, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                Spacer(Modifier.height(12.dp))
                Text(
                    strings.noFolder,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            scope.launch {
                                container.repository.moveRecordingToFolder(recordingId, null)
                                showMove = false
                            }
                        }
                        .padding(vertical = 14.dp),
                    fontSize = 16.sp,
                    color = VelumInk,
                )
                ThinDivider(0)
                folders.forEach { folder ->
                    Text(
                        folder.name,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                scope.launch {
                                    container.repository.moveRecordingToFolder(recordingId, folder.id)
                                    showMove = false
                                }
                            }
                            .padding(vertical = 14.dp),
                        fontSize = 16.sp,
                        color = VelumInk,
                    )
                    ThinDivider(0)
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
    if (showFindReplace) {
        FindReplaceDialog(
            onDismiss = { showFindReplace = false },
            onReplaceAll = { find, replace ->
                val current = recording?.transcript.orEmpty()
                if (find.isNotEmpty()) {
                    val updated = current.replace(find, replace)
                    scope.launch {
                        container.repository.updateTranscript(recordingId, updated)
                        showFindReplace = false
                    }
                } else {
                    showFindReplace = false
                }
            },
        )
    }
}

@Composable
private fun FindReplaceDialog(
    onDismiss: () -> Unit,
    onReplaceAll: (find: String, replace: String) -> Unit,
) {
    val strings = LocalStrings.current
    var find by remember { mutableStateOf("") }
    var replace by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.findReplace) },
        text = {
            Column {
                Text(strings.find, color = VelumInkSecondary, fontSize = 13.sp)
                BasicTextField(
                    value = find,
                    onValueChange = { find = it },
                    singleLine = true,
                    cursorBrush = SolidColor(VelumInk),
                    textStyle = TextStyle(color = VelumInk, fontSize = 16.sp),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                )
                ThinDivider(0)
                Spacer(Modifier.height(8.dp))
                Text(strings.replace, color = VelumInkSecondary, fontSize = 13.sp)
                BasicTextField(
                    value = replace,
                    onValueChange = { replace = it },
                    singleLine = true,
                    cursorBrush = SolidColor(VelumInk),
                    textStyle = TextStyle(color = VelumInk, fontSize = 16.sp),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onReplaceAll(find, replace) }) {
                Text(strings.replaceAll)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(strings.cancel) }
        },
    )
}

@Composable
private fun TabLabel(text: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            text,
            color = if (selected) VelumInk else VelumInkSecondary,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            fontSize = 16.sp,
        )
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .width(48.dp)
                .height(2.dp)
                .background(if (selected) VelumBlack else Color.Transparent),
        )
    }
}

@Composable
private fun SourcesPane(
    transcript: String?,
    durationSeconds: Long,
    positionSeconds: Long = 0,
    hasLocalAudio: Boolean,
    generating: Boolean,
    transportMessage: String? = null,
    onGenerate: () -> Unit,
    onPlaybackUnavailable: () -> Unit = {},
) {
    val strings = LocalStrings.current
    val dur = formatHms(durationSeconds)
    val pos = formatHms(positionSeconds.coerceIn(0, durationSeconds.coerceAtLeast(0)))
    Column(Modifier.fillMaxSize()) {
        Text(
            strings.transcription,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
            color = VelumInk,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        HorizontalDivider(thickness = 2.dp, color = VelumBlack, modifier = Modifier.padding(horizontal = 20.dp).width(90.dp))
        Spacer(Modifier.height(8.dp))
        PlayerBar(
            durationLabel = "$pos / $dur",
            enabled = hasLocalAudio,
            onTransportClick = onPlaybackUnavailable,
        )
        val playbackHint = when {
            !hasLocalAudio -> strings.ux.playbackUnavailable
            !transportMessage.isNullOrBlank() -> transportMessage
            else -> null
        }
        if (!playbackHint.isNullOrBlank()) {
            Text(
                playbackHint,
                color = Color(0xFFB45309),
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (generating) {
                GeneratingBody()
            } else {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(20.dp),
                ) {
                    MarkdownPreview(transcript.orEmpty())
                    Spacer(Modifier.height(24.dp))
                    OutlineButton(strings.retranscribe, onGenerate)
                }
            }
            if (generating) {
                AiGlowFooter(Modifier.align(Alignment.BottomCenter))
            }
        }
    }
}

@Composable
private fun LiveTranscriptPane(
    container: AppContainer,
    recordingId: String,
    title: String,
    initialTranscript: String? = null,
    initialElapsedSeconds: Long = 0L,
    onStoppedAndQueued: () -> Unit = {},
) {
    val strings = LocalStrings.current
    val seededTranscript = initialTranscript?.takeIf { it.isNotBlank() }
    var partial by remember(recordingId) {
        mutableStateOf(seededTranscript ?: "**partial** · 0:00\n")
    }
    var usingMic by remember { mutableStateOf(false) }
    var stopping by remember { mutableStateOf(false) }
    var inputSource by remember {
        val preferred = container.settingsRepository.preferences.value.defaultMicInput
        mutableStateOf(
            runCatching { MicInputSource.valueOf(preferred) }.getOrDefault(MicInputSource.Mic),
        )
    }
    var markerCount by remember { mutableStateOf(0) }
    var pausedUi by remember { mutableStateOf(container.micCapture.isPaused) }
    val finished = remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val streamer = remember {
        openStreamingTranscriber(container)
    }
    val wavPath = remember(recordingId) {
        "${recordingsDirectoryPath()}/$recordingId.wav"
    }
    // Only allocate WavWriter for the PCM-demo fallback. MicCapture/FGS owns the real file.
    val demoWavWriter = remember(recordingId) { mutableStateOf<WavWriter?>(null) }
    // Seeded transcript (Roborazzi / resume) — keep it visible; skip PCM demo overwrite.
    val preserveSeeded = seededTranscript != null

    fun finishOnce(): String {
        if (finished.value) return partial
        finished.value = true
        return runCatching { streamer.finish() }.getOrDefault(partial)
    }

    DisposableEffect(recordingId) {
        streamer.onPartial = { md ->
            if (!preserveSeeded) partial = md
        }
        val settingsLang = container.settingsRepository.settings.value.audioLanguage
        if (!preserveSeeded) {
            streamer.start(16_000, settingsLang, null)
        }

        val push: (ByteArray) -> Unit = { pcm ->
            try {
                streamer.pushPcm(pcm)
            } catch (t: Throwable) {
                println("VelumLivePcm: push failed: ${t.message ?: t}")
            }
        }

        var ownsMicSession = false
        val alreadyCapturing = container.micCapture.isCapturing
        if (alreadyCapturing) {
            container.micCapture.onPcmChunk = push
            usingMic = true
            markerCount = container.micCapture.markers.size
            pausedUi = container.micCapture.isPaused
        } else if (!preserveSeeded) {
            when (container.micCapture.start(wavPath, inputSource)) {
                MicStartResult.Started -> {
                    container.activeMicRecordingId = recordingId
                    container.micCapture.onPcmChunk = push
                    ownsMicSession = true
                    usingMic = true
                }
                else -> {
                    usingMic = false
                }
            }
        } else {
            usingMic = false
        }

        val demoJob = if (!usingMic && !preserveSeeded) {
            val writer = WavWriter(wavPath, sampleRateHz = 16_000)
            demoWavWriter.value = writer
            scope.launch {
                var tick = 0
                while (isActive && !finished.value) {
                    val chunk = demoPcmChunk(tick)
                    writer.writePcm(chunk)
                    streamer.pushPcm(chunk)
                    tick++
                    delay(400)
                }
            }
        } else {
            null
        }

        onDispose {
            demoJob?.cancel()
            container.micCapture.onPcmChunk = null
            // Do not stop an FAB-owned session when leaving the pane briefly;
            // only clear the PCM listener. Explicit Stop button finalizes.
            if (ownsMicSession && container.activeMicRecordingId == recordingId) {
                // Keep FGS running while status stays "recording".
            }
            finishOnce()
        }
    }

    Column(Modifier.fillMaxSize()) {
        Text(
            strings.liveTranscript,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
            color = VelumInk,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        HorizontalDivider(
            thickness = 2.dp,
            color = VelumBlack,
            modifier = Modifier.padding(horizontal = 20.dp).width(110.dp),
        )
        val elapsed = rememberLiveElapsedSeconds(
            running = !stopping && !finished.value && !pausedUi,
            initialSeconds = initialElapsedSeconds,
        )
        Text(
            formatDurationClock(elapsed),
            color = VelumInk,
            fontWeight = FontWeight.Medium,
            fontSize = 28.sp,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        LiveWaveform(
            active = !stopping && !finished.value && !pausedUi,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        if (preserveSeeded && !usingMic) {
            Text(
                "Phone mic · live · markers $markerCount",
                color = VelumInkSecondary,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            Row(
                Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlineButton(
                    if (pausedUi) "Resume" else "Pause",
                    { pausedUi = !pausedUi },
                    modifier = Modifier.weight(1f),
                )
                OutlineButton(
                    "Mark",
                    { markerCount += 1 },
                    modifier = Modifier.weight(1f),
                )
            }
        } else if (!usingMic) {
            // Robolectric / desktop: PCM demo path — keep a single quiet hint.
            Text(
                strings.streamingDemoHint,
                color = VelumInkSecondary,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        } else if (usingMic) {
            Text(
                "Input: ${inputSource.name} · markers $markerCount",
                color = VelumInkSecondary,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
            Row(
                Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlineButton(
                    if (pausedUi) "Resume" else "Pause",
                    {
                        if (pausedUi) {
                            container.micCapture.resume()
                            pausedUi = false
                        } else {
                            container.micCapture.pause()
                            pausedUi = true
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
                OutlineButton(
                    "Mark",
                    {
                        when (val r = container.micCapture.addMarker()) {
                            is MicMarkerResult.Ok -> markerCount = container.micCapture.markers.size
                            else -> Unit
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MicInputSource.entries.forEach { src ->
                    OutlineButton(
                        src.name,
                        { inputSource = src },
                        modifier = Modifier,
                    )
                }
            }
        }
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {
            if (title.isNotBlank()) {
                Text(title, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = VelumInkSecondary)
                Spacer(Modifier.height(8.dp))
            }
            MarkdownPreview(partial)
        }
        BlackButton(
            if (stopping) strings.generating else strings.stopRecording,
            {
                if (stopping) return@BlackButton
                stopping = true
                scope.launch {
                    val marks = container.micCapture.markers
                    val finalMd = withContext(Dispatchers.Default) {
                        val base = finishOnce()
                        if (marks.isEmpty()) base
                        else buildString {
                            append(base.trimEnd())
                            append("\n\n## Markers\n")
                            marks.forEach { m ->
                                val sec = m.offsetMs / 1000
                                val mm = sec / 60
                                val ss = sec % 60
                                append("- **")
                                append(mm)
                                append(':')
                                append(ss.toString().padStart(2, '0'))
                                append("**")
                                if (m.label.isNotBlank()) {
                                    append(' ')
                                    append(m.label)
                                }
                                append('\n')
                            }
                        }
                    }
                    container.repository.updateTranscript(recordingId, finalMd)
                    if (container.activeMicRecordingId == recordingId ||
                        container.micCapture.isCapturing
                    ) {
                        when (val stopped = container.micCapture.stop()) {
                            is MicStopResult.Stopped -> {
                                container.repository.updateLocalPath(
                                    id = recordingId,
                                    localPath = stopped.absolutePath,
                                    durationSeconds = stopped.durationSeconds,
                                    sizeBytes = stopped.sizeBytes,
                                )
                            }
                            else -> {
                                // Notification Stop may have already finalized; fix header from disk.
                                WavWriter.recoverOrFinalize(wavPath)?.let { recovered ->
                                    container.repository.updateLocalPath(
                                        id = recordingId,
                                        localPath = recovered.absolutePath,
                                        durationSeconds = recovered.durationSeconds,
                                        sizeBytes = recovered.sizeBytes,
                                    )
                                }
                            }
                        }
                        container.activeMicRecordingId = null
                    } else {
                        val finishedWav = withContext(Dispatchers.IO) {
                            demoWavWriter.value?.finish()
                                ?: WavWriter(wavPath, sampleRateHz = 16_000).finish()
                        }
                        container.repository.updateLocalPath(
                            id = recordingId,
                            localPath = finishedWav.absolutePath,
                            durationSeconds = finishedWav.durationSeconds,
                            sizeBytes = finishedWav.sizeBytes,
                        )
                    }
                    container.repository.updateStatus(recordingId, "queued")
                    container.enqueueProcessing(
                        recordingId = recordingId,
                        templateId = "velum.meeting",
                        retranscribe = true,
                    )
                    onStoppedAndQueued()
                }
            },
            modifier = Modifier.padding(20.dp),
        )
    }
}

private fun openStreamingTranscriber(container: AppContainer): StreamingTranscriber {
    val lang = container.settingsRepository.settings.value.audioLanguage
    val offlineId = LocalAsrRouter.chooseModelId(lang)
    val preferredIds = listOf(
        // Prefer true OnlineRecognizer packs when downloaded.
        "streaming-zipformer-es-kroko".takeIf {
            lang?.lowercase()?.startsWith("es") == true
        },
        "streaming-zipformer-en-20m-mobile".takeIf {
            lang.isNullOrBlank() || lang.lowercase().startsWith("en")
        },
        "streaming-zipformer-multilang-2025-02",
        "streaming-zipformer-en-20m-mobile",
        offlineId,
    ).filterNotNull().distinct()
    for (modelId in preferredIds) {
        val path = container.localModelManager.modelPath(modelId)?.absolutePath ?: continue
        if (container.localModelManager.stateFor(modelId)?.status != LocalModelStatus.Ready) continue
        val created = runCatching { createStreamingTranscriber(path, modelId) }.getOrNull()
        if (created != null) return created
    }
    return FakeStreamingTranscriber()
}

/** Synthetic PCM16 mono chunk (~640 ms) for offline streaming demos. */
private fun demoPcmChunk(tick: Int): ByteArray {
    val sampleRate = 16_000
    val samples = sampleRate * 2 / 5
    val out = ByteArray(samples * 2)
    val freq = 220.0 + (tick % 7) * 40.0
    var i = 0
    while (i < samples) {
        val t = i.toDouble() / sampleRate
        val sample = (kotlin.math.sin(2.0 * kotlin.math.PI * freq * t) * 4000).toInt().toShort()
        out[i * 2] = (sample.toInt() and 0xFF).toByte()
        out[i * 2 + 1] = (sample.toInt() shr 8 and 0xFF).toByte()
        i++
    }
    return out
}

@Composable
private fun NotesPane(
    summary: String?,
    generating: Boolean,
    processError: String? = null,
    onAskAi: () -> Unit,
    onGenerate: () -> Unit,
    onMindMap: () -> Unit,
) {
    val strings = LocalStrings.current
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Text(
                strings.summary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                color = VelumInk,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            HorizontalDivider(thickness = 2.dp, color = VelumBlack, modifier = Modifier.padding(horizontal = 20.dp).width(70.dp))
            if (!processError.isNullOrBlank()) {
                Text(
                    processError,
                    color = Color(0xFFFF3B30),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            if (generating || summary.isNullOrBlank()) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    GeneratingBody()
                    AiGlowFooter(Modifier.align(Alignment.BottomCenter))
                }
            } else {
                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(20.dp),
                ) {
                    MarkdownPreview(summary)
                    Spacer(Modifier.height(20.dp))
                    OutlineButton(strings.mindMap, onMindMap)
                    Spacer(Modifier.height(12.dp))
                    BlackButton(strings.askAi, onAskAi)
                    Spacer(Modifier.height(12.dp))
                    OutlineButton(strings.generateCustom, onGenerate)
                }
            }
        }
    }
}

@Composable
private fun PlayerBar(
    durationLabel: String,
    enabled: Boolean = true,
    onTransportClick: () -> Unit = {},
) {
    val strings = LocalStrings.current
    val tint = if (enabled) VelumInk else VelumInkSecondary
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .alpha(if (enabled) 1f else 0.45f),
    ) {
        HorizontalDivider(color = VelumDivider, thickness = 0.5.dp)
        Spacer(Modifier.height(10.dp))
        Text(durationLabel, color = VelumInkSecondary, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(36.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFFF2F2F7))
                .clickable(enabled = enabled, onClick = onTransportClick),
        ) {
            Row(
                Modifier.fillMaxSize().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                repeat(48) { i ->
                    val h = 6 + (i % 7) * 3
                    Box(
                        Modifier
                            .width(3.dp)
                            .height(h.dp)
                            .background(VelumInkSecondary.copy(alpha = 0.45f), RoundedCornerShape(1.dp)),
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .defaultMinSize(48.dp, 48.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFF2F2F7))
                    .clickable(enabled = enabled, onClick = onTransportClick),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.PlayArrow,
                    contentDescription = strings.play,
                    tint = tint,
                    modifier = Modifier.size(24.dp),
                )
            }
            Box(
                Modifier
                    .defaultMinSize(48.dp, 48.dp)
                    .clickable(enabled = enabled, onClick = onTransportClick),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Outlined.Undo,
                    contentDescription = "−15",
                    tint = tint,
                    modifier = Modifier.size(26.dp),
                )
            }
            Box(
                Modifier
                    .defaultMinSize(48.dp, 48.dp)
                    .clickable(enabled = enabled, onClick = onTransportClick),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Outlined.Redo,
                    contentDescription = "+15",
                    tint = tint,
                    modifier = Modifier.size(26.dp),
                )
            }
            Text(
                "1x",
                color = tint,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .defaultMinSize(48.dp, 48.dp)
                    .clickable(enabled = enabled, onClick = onTransportClick)
                    .padding(horizontal = 8.dp, vertical = 12.dp),
            )
            Box(
                Modifier
                    .defaultMinSize(48.dp, 48.dp)
                    .clickable(enabled = enabled, onClick = onTransportClick),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.AutoAwesome,
                    contentDescription = strings.askAi,
                    tint = tint,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        HorizontalDivider(color = VelumDivider, thickness = 0.5.dp)
    }
}

@Composable
private fun GeneratingBody() {
    val strings = LocalStrings.current
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        repeat(4) { i ->
            Box(
                Modifier
                    .fillMaxWidth(0.9f - i * 0.12f)
                    .height(12.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFFE8E8ED)),
            )
            Spacer(Modifier.height(14.dp))
        }
        Spacer(Modifier.height(24.dp))
        Text(strings.generating, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = VelumInk)
        Spacer(Modifier.height(8.dp))
        Text(
            strings.generatingSubtitle,
            color = VelumInkSecondary,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        )
    }
}

data class SpeakerLabelRow(
    val id: String,
    val label: String,
    val displayName: String?,
    val talkSeconds: Long,
    /** Cosine confidence when auto-matched to a voice profile; null if unlabeled. */
    val matchConfidence: Float? = null,
)

@Composable
fun SpeakerLabelSheetContent(
    onClose: () -> Unit,
    speakers: List<SpeakerLabelRow> = emptyList(),
    onConfirm: (Map<String, String>) -> Unit = { onClose() },
) {
    val strings = LocalStrings.current
    fun defaultSpeakerName(label: String): String {
        val n = label.trimStart('S', 's').toIntOrNull() ?: 1
        return strings.ux.speakerLabel.replace("%d", n.toString())
    }
    val rows = speakers.ifEmpty {
        listOf(
            SpeakerLabelRow("demo-1", "S1", defaultSpeakerName("S1"), 312),
            SpeakerLabelRow("demo-2", "S2", defaultSpeakerName("S2"), 220),
        )
    }
    var names by remember(rows, strings.localeTag) {
        mutableStateOf(
            rows.associate { row ->
                row.id to (row.displayName?.takeIf { it.isNotBlank() }
                    ?: defaultSpeakerName(row.label))
            },
        )
    }
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(strings.nameSpeakers, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            Box(
                Modifier
                    .defaultMinSize(48.dp, 48.dp)
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                Text("×", fontSize = 22.sp, color = VelumInkSecondary)
            }
        }
        rows.forEach { row ->
            val dur = formatTalk(row.talkSeconds)
            Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .border(1.dp, VelumDivider, RoundedCornerShape(8.dp))
                        .padding(14.dp),
                ) {
                    BasicTextField(
                        value = names[row.id].orEmpty(),
                        onValueChange = { value -> names = names + (row.id to value) },
                        singleLine = true,
                        textStyle = TextStyle(color = VelumInk, fontSize = 15.sp),
                        cursorBrush = SolidColor(VelumInk),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    buildString {
                        append("${strings.totalDuration}: $dur")
                        row.matchConfidence?.let {
                            append(" · match ")
                            append("%.0f".format(it * 100))
                            append('%')
                        }
                    },
                    color = VelumInkSecondary,
                    fontSize = 13.sp,
                )
            }
        }
        Text(
            strings.speakersUpdateNote + " Confirmations refine the on-device voice profile.",
            color = VelumInkSecondary,
            fontSize = 12.sp,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        )
        DualActionRow(strings.cancel, strings.confirm, onClose, { onConfirm(names) })
    }
}

private fun formatTalk(seconds: Long): String {
    val m = seconds / 60
    val s = seconds % 60
    return "${m}m ${s}s"
}

@Composable
fun GenerateSheetContent(
    onClose: () -> Unit,
    onApply: (templateId: String, language: String, autoLabel: Boolean) -> Unit = { _, _, _ -> onClose() },
    templates: List<String> = listOf("velum.meeting", "velum.general", "velum.it"),
    languages: List<String> = listOf("auto", "pt", "en", "es"),
) {
    val strings = LocalStrings.current
    var auto by remember { mutableStateOf(true) }
    var templateIdx by remember { mutableStateOf(0) }
    var autoLabel by remember { mutableStateOf(true) }
    var langIdx by remember { mutableStateOf(0) }
    val templateLabels = templates.map { id ->
        BuiltinTemplateCatalog.get(id)?.displayName(strings.localeTag) ?: id
    }
    Column(Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
        Text(
            if (auto) strings.generateAuto else strings.generateCustom,
            fontWeight = FontWeight.Thin,
            fontSize = 26.sp,
            color = VelumInk,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ModeChip(strings.generateAuto, auto) { auto = true }
            ModeChip(strings.generateCustom, !auto) { auto = false }
        }
        Spacer(Modifier.height(8.dp))
        if (!auto) {
            ChevronRow(
                strings.template,
                {
                    templateIdx = (templateIdx + 1) % templates.size.coerceAtLeast(1)
                },
                value = templateLabels.getOrElse(templateIdx) { templates[templateIdx] },
            )
        }
        ChevronRow(
            strings.autoLabelVoice,
            { autoLabel = !autoLabel },
            value = if (autoLabel) strings.on else strings.off,
        )
        ChevronRow(
            strings.audioLanguage,
            { langIdx = (langIdx + 1) % languages.size.coerceAtLeast(1) },
            value = languages[langIdx],
            showDivider = false,
        )
        Spacer(Modifier.height(12.dp))
        BlackButton(
            strings.apply,
            {
                val templateId = if (auto) {
                    "velum.meeting"
                } else {
                    templates.getOrElse(templateIdx) { "velum.meeting" }
                }
                onApply(templateId, languages[langIdx], autoLabel)
            },
            modifier = Modifier.padding(horizontal = 20.dp),
        )
    }
}

@Composable
private fun ModeChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) VelumBlack else VelumBg)
            .border(1.dp, if (selected) VelumBlack else VelumDivider, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(text, color = if (selected) VelumOnBlack else VelumInk, fontSize = 13.sp)
    }
}

private fun formatHms(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    fun two(n: Long) = if (n < 10) "0$n" else "$n"
    return "${two(h)}:${two(m)}:${two(s)}"
}
