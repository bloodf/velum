package dev.velum.app.ui.recording

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Search
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.velum.ai.engine.AiTask
import dev.velum.ai.engine.ModeResolution
import dev.velum.ai.engine.StreamingTranscriber
import dev.velum.ai.engine.createStreamingTranscriber
import dev.velum.ai.local.LocalAsrRouter
import dev.velum.ai.local.LocalModelStatus
import dev.velum.app.di.AppContainer
import dev.velum.app.export.RecordingExport
import dev.velum.app.export.copyToClipboard
import dev.velum.app.export.shareBytes
import dev.velum.app.i18n.LocalStrings
import dev.velum.app.i18n.formatFixed
import dev.velum.app.i18n.processingFailureText
import dev.velum.app.platform.AudioPlayer
import dev.velum.app.platform.MicInputSource
import dev.velum.app.platform.MicMarkerResult
import dev.velum.app.platform.MicStartResult
import dev.velum.app.platform.createAudioPlayer
import dev.velum.app.platform.MicStopResult
import dev.velum.app.i18n.processingWarningText
import dev.velum.app.platform.finalizeMicRecording
import dev.velum.app.platform.persistLiveMarkers
import dev.velum.app.platform.localizedLabel
import dev.velum.app.platform.HapticKind
import dev.velum.app.platform.LocalHaptics
import dev.velum.app.platform.canExportPdf
import dev.velum.app.platform.recordingsDirectoryPath
import dev.velum.app.platform.sharePdf
import dev.velum.app.platform.shareText
import dev.velum.app.ui.components.AiGlowFooter
import dev.velum.app.ui.components.BlackButton
import dev.velum.app.ui.components.ChevronRow
import dev.velum.app.ui.components.DualActionRow
import dev.velum.app.ui.components.EmptyState
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
import dev.velum.core.data.epochSecondsNow
import dev.velum.core.db.Summary_version
import dev.velum.core.db.Template
import dev.velum.core.model.AiModelInfo
import dev.velum.core.model.ComputeMode
import dev.velum.core.search.FtsQuery
import dev.velum.core.search.RecordingSearch
import dev.velum.core.settings.ApiEndpointProfile
import dev.velum.core.settings.ApiModelOverride
import dev.velum.core.templates.BuiltinTemplateCatalog
import dev.velum.core.templates.TemplateTagFilter
import dev.velum.core.templates.TemplateTagVocabulary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
    onMovedToTrash: (String) -> Unit = {},
) {
    val strings = LocalStrings.current
    val haptics = LocalHaptics.current
    val recording by remember(container, recordingId) { container.repository.observeRecording(recordingId) }.collectAsState(null)
    val folders by remember(container) { container.repository.observeFolders() }.collectAsState(emptyList())
    var tab by rememberSaveable(recordingId) { mutableStateOf(initialTab) } // 0 fontes, 1 notas
    var seekSeconds by remember(recordingId, initialSeekSeconds) {
        mutableStateOf(initialSeekSeconds.coerceAtLeast(0))
    }
    var showTrashConfirm by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showSpeakers by remember { mutableStateOf(showSpeakersInitially) }
    var showGenerate by remember { mutableStateOf(showGenerateInitially) }
    var showChangeTemplate by remember { mutableStateOf(false) }
    var showSummaryVersions by remember { mutableStateOf(false) }
    var versionPendingDelete by remember { mutableStateOf<Summary_version?>(null) }
    var showMove by remember { mutableStateOf(false) }
    var showFindReplace by remember { mutableStateOf(false) }
    var showAskAi by remember { mutableStateOf(false) }
    var showMindMap by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var renameDraft by remember { mutableStateOf("") }
    var exportStatus by remember { mutableStateOf<String?>(null) }
    var askDraft by remember { mutableStateOf("") }
    var askAnswer by remember { mutableStateOf<String?>(null) }
    var askBusy by remember { mutableStateOf(false) }
    var askError by remember { mutableStateOf<String?>(null) }
    var processError by remember { mutableStateOf<String?>(null) }
    var lastFailure by remember { mutableStateOf<String?>(null) }
    var pendingRetranscribe by remember { mutableStateOf<Boolean?>(null) }
    // The request that hit the missing-model prompt, so switching to API reruns it with the same template/model.
    var pendingTemplateId by remember { mutableStateOf<String?>(null) }
    var pendingApiOverride by remember { mutableStateOf<ApiModelOverride?>(null) }
    var modelPrompt by remember { mutableStateOf<ModeResolution.NeedLocalModel?>(null) }
    val scope = rememberCoroutineScope()
    val audioPlayer = remember(recordingId) { createAudioPlayer() }
    DisposableEffect(recordingId) {
        onDispose { audioPlayer.release() }
    }
    val localPath = recording?.local_path
    LaunchedEffect(localPath, recordingId) {
        val path = localPath
        if (!path.isNullOrBlank()) {
            val ok = audioPlayer.prepare(path)
            afterPrepare(
                ok = ok,
                seekMs = seekSeconds * 1000L,
                seekTo = audioPlayer::seekTo,
                onUnavailable = { processError = "playback:" + strings.ux.playbackUnavailable },
            )
        }
    }
    val isLiveRecording = recording?.status == "recording"
    val pausedThermal = recording?.status == "paused_thermal"
    val isFailed = recording?.status == "failed"
    LaunchedEffect(recordingId, recording?.status) {
        lastFailure = if (recording?.status == "failed") container.repository.getProcessingError(recordingId) else null
    }
    // Only true while a job is actively running — blank notes on a ready recording
    // must show an empty CTA, not the forever "Generating" skeleton.
    val generating = recording != null && !isLiveRecording && (
        recording?.status == "generating" || pausedThermal
        )

    fun enqueueAfterGate(
        retranscribe: Boolean,
        templateId: String? = "velum.meeting",
        apiModelOverride: ApiModelOverride? = null,
    ) {
        processError = null
        // Change template reuses the stored transcript, so only the Summary route must be ready.
        val tasks = if (retranscribe) {
            listOf(AiTask.Transcription, AiTask.Diarization, AiTask.Summary)
        } else {
            listOf(AiTask.Summary)
        }
        for (task in tasks) {
            when (val resolution = container.aiRouter.resolveModeOrPrompt(task, container.localModelManager)) {
                is ModeResolution.NeedLocalModel -> {
                    pendingRetranscribe = retranscribe
                    pendingTemplateId = templateId
                    pendingApiOverride = apiModelOverride
                    modelPrompt = resolution
                    return
                }
                is ModeResolution.Proceed -> Unit
            }
        }
        scope.launch {
            try {
                if (container.enqueueProcessing(
                        recordingId = recordingId,
                        templateId = templateId,
                        retranscribe = retranscribe,
                        apiModelOverride = apiModelOverride,
                    ) == dev.velum.ai.pipeline.EnqueueResult.REJECTED) processError = strings.ux.genericError
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                processError = strings.ux.genericError
            }
        }
    }

    fun launchProcessing(
        retranscribe: Boolean,
        templateId: String? = "velum.meeting",
        apiModelOverride: ApiModelOverride? = null,
    ) = enqueueAfterGate(retranscribe, templateId, apiModelOverride)

    fun exportMarkdownShare() {
        val rec = recording ?: return
        shareText(
            rec.title,
            RecordingExport.markdown(
                title = rec.title,
                transcript = rec.transcript.orEmpty(),
                summary = rec.summary_markdown.orEmpty(),
            ),
        )
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
                    .clickable { showExport = true },
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
                    DropdownMenuItem({ Text(strings.ux.rename) }, {
                        showMenu = false
                        renameDraft = recording?.title.orEmpty()
                        showRename = true
                    })
                    DropdownMenuItem({ Text(strings.moveToFolder) }, {
                        showMenu = false
                        showMove = true
                    })
                    DropdownMenuItem({ Text(strings.findReplace) }, {
                        showMenu = false
                        showFindReplace = true
                    })
                    // A recording still being captured has a growing file: retranscribing it would read a partial WAV.
                    if (!isLiveRecording) {
                        DropdownMenuItem({ Text(strings.retranscribe) }, {
                            showMenu = false
                            launchProcessing(retranscribe = true)
                        })
                    }
                    DropdownMenuItem({ Text(strings.nameSpeakers) }, {
                        showMenu = false
                        showSpeakers = true
                    })
                    DropdownMenuItem({ Text(strings.moveToTrash) }, {
                        showMenu = false
                        showTrashConfirm = true
                    })
                }
            }
            Spacer(Modifier.width(8.dp))
        }

        if (showTrashConfirm) {
            AlertDialog(
                onDismissRequest = { showTrashConfirm = false },
                title = { Text(strings.ux.confirmTrashTitle) },
                text = { Text(strings.ux.confirmTrashBody) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            haptics.perform(HapticKind.Medium)
                            showTrashConfirm = false
                            scope.launch {
                                container.repository.trashRecording(recordingId)
                                onMovedToTrash(recordingId)
                                onBack()
                            }
                        },
                    ) { Text(strings.confirm) }
                },
                dismissButton = {
                    TextButton(onClick = { showTrashConfirm = false }) { Text(strings.cancel) }
                },
            )
        }

        if (showRename) {
            AlertDialog(
                onDismissRequest = { showRename = false },
                title = { Text(strings.ux.renameRecording) },
                text = {
                    BasicTextField(
                        value = renameDraft,
                        onValueChange = { renameDraft = it },
                        singleLine = true,
                        textStyle = TextStyle(color = VelumInk, fontSize = 16.sp),
                        cursorBrush = SolidColor(VelumInk),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, VelumDivider, RoundedCornerShape(8.dp))
                            .padding(12.dp),
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val title = renameDraft.trim()
                            showRename = false
                            if (title.isNotEmpty()) {
                                scope.launch {
                                    container.repository.updateRecordingTitle(recordingId, title)
                                }
                            }
                        },
                    ) { Text(strings.confirm) }
                },
                dismissButton = {
                    TextButton(onClick = { showRename = false }) { Text(strings.cancel) }
                },
            )
        }

        if (pausedThermal) {
            Text(
                strings.pausedThermal,
                color = Color(0xFFB45309),
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }

        strings.processingWarningText(recording?.processing_warning)?.let { warning ->
            Text(
                warning,
                color = Color(0xFFB45309),
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }

        if (isFailed) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text(
                    strings.ux.failedProcessing,
                    color = Color(0xFFB45309),
                    fontSize = 14.sp,
                )
                processingFailureText(lastFailure, strings)?.let {
                    Text(it, color = VelumInkSecondary, fontSize = 12.sp)
                }
                Spacer(Modifier.height(8.dp))
                OutlineButton(
                    text = strings.ux.retryProcessing,
                    onClick = { launchProcessing(retranscribe = true) },
                )
            }
        }

        if (!exportStatus.isNullOrBlank()) {
            Text(
                exportStatus.orEmpty(),
                color = VelumInkSecondary,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
            )
        }

        if (isLiveRecording) {
            // The tablet pane reuses this slot: state and transcript must never carry over to another recording.
            key(recordingId) {
                LiveTranscriptPane(
                    container = container,
                    recordingId = recordingId,
                    title = recording?.title.orEmpty(),
                    // Markers are not speech: a markers-only transcript must not count as a seeded one.
                    initialTranscript = dev.velum.core.data.TranscriptMarkers.spoken(recording?.transcript),
                    initialElapsedSeconds = recording?.duration_seconds ?: 0L,
                    onStoppedAndQueued = { /* status observed via Flow */ },
                )
            }
        } else if (tab == 0) {
            SourcesPane(
                player = audioPlayer,
                transcript = recording?.transcript,
                durationSeconds = recording?.duration_seconds ?: 0,
                hasLocalAudio = !recording?.local_path.isNullOrBlank(),
                generating = generating && dev.velum.core.data.TranscriptMarkers.spoken(recording?.transcript).isNullOrBlank(),
                transportMessage = processError?.takeIf { it.startsWith("playback:") }?.removePrefix("playback:"),
                onGenerate = { showGenerate = true },
                onAskAi = { showAskAi = true },
                onSeekSeconds = { seconds ->
                    seekSeconds = seconds
                    audioPlayer.seekTo(seconds * 1000L)
                },
            )
        } else {
            NotesPane(
                summary = recording?.summary_markdown,
                generating = generating,
                processError = processError,
                onAskAi = { showAskAi = true },
                onGenerate = { showGenerate = true },
                onChangeTemplate = { showChangeTemplate = true },
                onSummaryVersions = { showSummaryVersions = true },
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

    if (showExport) {
        ModalBottomSheet(
            onDismissRequest = { showExport = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = VelumSurface,
        ) {
            val rec = recording
            val title = rec?.title.orEmpty().ifBlank { "Velum" }
            val transcript = rec?.transcript.orEmpty()
            val summary = rec?.summary_markdown.orEmpty()
            val md = RecordingExport.markdown(title, transcript, summary)
            Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                Text(
                    strings.ux.exportAs,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp,
                    color = VelumInk,
                )
                Spacer(Modifier.height(12.dp))
                ExportOption(strings.ux.exportMarkdown) {
                    showExport = false
                    shareText(title, md)
                }
                ExportOption(strings.ux.exportPlainText) {
                    showExport = false
                    shareText(title, RecordingExport.plainText(md))
                }
                ExportOption(strings.ux.exportSrt) {
                    showExport = false
                    val srt = RecordingExport.srt(transcript).ifBlank {
                        RecordingExport.srt("**Recording** · 0:00\n${transcript.ifBlank { "…" }}")
                    }
                    shareText("$title.srt", srt)
                }
                ExportOption(strings.ux.exportDocx) {
                    showExport = false
                    val safe = title.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "velum" }
                    scope.launch {
                        val docx = withContext(Dispatchers.Default) { RecordingExport.docxMinimal(md) }
                        shareBytes("$safe.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", docx)
                    }
                }
                if (canExportPdf()) {
                    ExportOption(strings.ux.exportPdf) {
                        showExport = false
                        scope.launch { sharePdf(title, RecordingExport.plainText(md)) }
                    }
                }
                ExportOption(strings.ux.copyTranscript) {
                    showExport = false
                    copyToClipboard(RecordingExport.plainText(transcript.ifBlank { md }))
                    exportStatus = strings.ux.copiedToClipboard
                }
                Spacer(Modifier.height(24.dp))
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
                            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                throw cancelled
                            } catch (_: Throwable) {
                                askError = strings.ux.genericError
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
            val speakers by remember(container, recordingId) { container.repository.observeSpeakers(recordingId) }.collectAsState(emptyList())
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
                    // The rows the user typed against, taken at the tap: a background job may replace them while the coroutine runs.
                    val shown = speakers
                    scope.launch {
                        try {
                            val applied = confirmSpeakerNames(
                                shown = shown,
                                current = { container.repository.getSpeakers(recordingId) },
                                renames = renames,
                                strings = strings,
                                enrol = { row, trimmed ->
                                var cacheMissed = false
                                // The name is already saved; a voice that cannot be enrolled is reported like a missing sample.
                                try {
                                container.voiceProfileStore.withReady {
                                    val emb = cachedClusterEmbedding(
                                        recordingId,
                                        row.label,
                                    )
                                    if (emb == null) cacheMissed = true // nothing to save a voice from
                                    val existing = inspectProfiles().profiles
                                        .firstOrNull { it.displayName.equals(trimmed, ignoreCase = true) }
                                    when {
                                        existing != null && emb != null ->
                                            refine(existing.id, emb)
                                        else ->
                                            promoteCached(
                                                recordingId = recordingId,
                                                speakerId = row.label,
                                                displayName = trimmed,
                                            )
                                    }
                                }
                                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    cacheMissed = true
                                }
                                if (cacheMissed) processError = strings.ux.voiceProfilesNoCachedVoice
                                },
                                rename = { row, trimmed -> container.repository.renameSpeakerIfUnchanged(recordingId, row, trimmed) },
                            )
                            // Set last, so the mismatch message wins over the missing-voice one.
                            if (!applied) processError = strings.added.speakersChangedWhileOpen
                            showSpeakers = false
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            processError = strings.ux.genericError
                        }
                    }
                },
            )
        }
    }
    if (showGenerate || showChangeTemplate) {
        val changeOnly = showChangeTemplate
        ModalBottomSheet(
            onDismissRequest = {
                showGenerate = false
                showChangeTemplate = false
            },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = VelumSurface,
        ) {
            val templatesFlow = remember(container) { container.repository.observeTemplates() }
            val catalogTemplates by templatesFlow.collectAsState(emptyList())
            val aiSettings by container.settingsRepository.settings.collectAsState()
            GenerateSheetContent(
                onClose = {
                    showGenerate = false
                    showChangeTemplate = false
                },
                catalogTemplates = catalogTemplates.filter { it.builtin != 0L && it.featured != 0L },
                searchTemplateIds = { match ->
                    container.repository.searchTemplateIdsFts(match)
                },
                profiles = aiSettings.profiles,
                modelsByProfile = aiSettings.cachedModelsByProfile,
                onApply = { templateId, language, autoLabel, apiOverride ->
                    showGenerate = false
                    showChangeTemplate = false
                    scope.launch {
                        if (language != "auto") {
                            container.settingsRepository.setAudioLanguage(language)
                        }
                        container.settingsRepository.setAutoSpeakerLabel(autoLabel)
                        launchProcessing(
                            retranscribe = !changeOnly,
                            templateId = templateId,
                            apiModelOverride = apiOverride,
                        )
                    }
                },
            )
        }
    }
    if (showSummaryVersions) {
        LaunchedEffect(recordingId) {
            container.repository.ensureBaselineVersion(recordingId)
        }
        val versions by remember(container, recordingId) { container.repository.observeSummaryVersions(recordingId) }
            .collectAsState(emptyList())
        ModalBottomSheet(
            onDismissRequest = { showSummaryVersions = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = VelumSurface,
        ) {
            SummaryVersionsSheet(
                versions = versions,
                onActivate = { versionId ->
                    scope.launch {
                        container.repository.activateSummaryVersion(recordingId, versionId)
                    }
                },
                onDelete = { version -> versionPendingDelete = version },
                onClose = { showSummaryVersions = false },
            )
        }
    }
    versionPendingDelete?.let { pending ->
        AlertDialog(
            onDismissRequest = { versionPendingDelete = null },
            title = { Text(strings.ux.confirmDeleteTitle) },
            text = { Text(strings.confirmDeleteVersionBody) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val id = pending.id
                        versionPendingDelete = null
                        scope.launch {
                            container.repository.deleteSummaryVersion(recordingId, id)
                        }
                    },
                ) { Text(strings.confirm) }
            },
            dismissButton = {
                TextButton(onClick = { versionPendingDelete = null }) {
                    Text(strings.cancel)
                }
            },
        )
    }
    modelPrompt?.let { need ->
        LocalModelMissingDialog(
            modelDisplayName = need.displayName,
            onDownload = {
                modelPrompt = null
                pendingRetranscribe = null
                pendingTemplateId = null
                pendingApiOverride = null
                onOpenModelManager()
            },
            // Diarization has no API route, so its prompt has no such button.
            onSwitchToApi = if (need.task == AiTask.Diarization) null else {
                {
                    if (modelPrompt === need) {
                        // Take the pending request and close the prompt synchronously, so a second tap that lands
                        // before recomposition finds nothing to run instead of enqueueing a default-template job.
                        val retranscribe = pendingRetranscribe ?: true
                        val templateId = pendingTemplateId
                        val apiOverride = pendingApiOverride
                        modelPrompt = null
                        pendingRetranscribe = null
                        pendingTemplateId = null
                        pendingApiOverride = null
                        scope.launch {
                            // A storage failure is reported like on the other settings screens and nothing is enqueued.
                            val switched = container.settingsViewModel.handleStorageFailure {
                                when (need.task) {
                                    AiTask.Transcription ->
                                        container.settingsRepository.setTranscriptionMode(ComputeMode.Api)
                                    AiTask.Summary ->
                                        container.settingsRepository.setSummaryMode(ComputeMode.Api)
                                    AiTask.Diarization -> Unit
                                }
                            }
                            if (switched) {
                                enqueueAfterGate(retranscribe, templateId, apiOverride)
                            } else {
                                // Visible on this screen even when the app-level storage notice was already shown.
                                processError = strings.ux.secureStorageUnavailable
                            }
                        }
                    }
                }
            },
            onDismiss = {
                modelPrompt = null
                pendingRetranscribe = null
                pendingTemplateId = null
                pendingApiOverride = null
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
private fun ExportOption(label: String, onClick: () -> Unit) {
    Text(
        label,
        color = VelumInk,
        fontSize = 16.sp,
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
    )
    ThinDivider(0)
}

@Composable
private fun SourcesPane(
    player: AudioPlayer,
    transcript: String?,
    durationSeconds: Long,
    hasLocalAudio: Boolean,
    generating: Boolean,
    transportMessage: String? = null,
    onGenerate: () -> Unit,
    onAskAi: () -> Unit = {},
    onSeekSeconds: (Long) -> Unit = {},
) {
    val strings = LocalStrings.current
    val playing by player.isPlaying.collectAsState()
    val positionMs by player.positionMs.collectAsState()
    val durationMs by player.durationMs.collectAsState()
    val speed by player.playbackSpeed.collectAsState()
    val skipSilence by player.skipSilence.collectAsState()
    val effectiveDurationMs = when {
        durationMs > 0L -> durationMs
        durationSeconds > 0L -> durationSeconds * 1000L
        else -> 0L
    }
    val dur = formatHms((effectiveDurationMs / 1000L).coerceAtLeast(0))
    val pos = formatHms((positionMs / 1000L).coerceIn(0, (effectiveDurationMs / 1000L).coerceAtLeast(0)))
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
            isPlaying = playing,
            speedLabel = formatSpeedLabel(speed),
            skipSilence = skipSilence,
            progress = if (effectiveDurationMs > 0) {
                (positionMs.toFloat() / effectiveDurationMs.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            },
            onPlayPause = {
                if (playing) player.pause() else player.play()
            },
            onSeekBack = { player.seekBy(-15_000L) },
            onSeekForward = { player.seekBy(15_000L) },
            onCycleSpeed = { player.cycleSpeed() },
            onToggleSkipSilence = { player.setSkipSilence(!skipSilence) },
            onAskAi = onAskAi,
            onSeekFraction = { fraction ->
                if (effectiveDurationMs > 0) {
                    player.seekTo((effectiveDurationMs * fraction).toLong())
                }
            },
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
                    SeekableTranscript(
                        transcript = transcript.orEmpty(),
                        onSeekMs = { ms -> onSeekSeconds(ms / 1000L) },
                    )
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

/** Whether [typed] differs from what the speaker sheet showed for this row: its saved name, else the default label. */
internal fun speakerNameChanged(storedName: String?, label: String, typed: String, strings: dev.velum.app.i18n.Strings): Boolean {
    val defaultName = strings.ux.speakerLabel.replace("%d", (label.trimStart('S', 's').toIntOrNull() ?: 1).toString())
    return typed != (storedName?.takeIf { it.isNotBlank() } ?: defaultName)
}

/**
 * A transcript line that is not a cue header, as drawn: the "## Markers" section heading in the app
 * language and "- **0:12** label" as a plain bullet, never with their Markdown markers.
 */
internal fun transcriptBodyLineDisplay(line: String, strings: dev.velum.app.i18n.Strings): String {
    val trimmed = line.trim()
    if (trimmed == dev.velum.ai.pipeline.TranscriptFormatter.MARKERS_HEADING) return strings.ux.markersHeading
    if (trimmed.startsWith("- **")) return "• " + trimmed.removePrefix("- ").replace("**", "")
    return line.replace("**", "")
}

/**
 * Cue header as drawn: the stored `**Name** · 0:00` without its Markdown markers, and the generated
 * English "Speaker N" in the app language. The stored text and exports are unchanged.
 */
internal fun transcriptHeaderDisplay(line: String, strings: dev.velum.app.i18n.Strings): String {
    val plain = line.trim().replace("**", "")
    val generated = Regex("""^Speaker (\d+)(?= ·)""").find(plain) ?: return plain
    return strings.ux.speakerLabel.replace("%d", generated.groupValues[1]) + plain.removePrefix(generated.value)
}

@Composable
private fun SeekableTranscript(
    transcript: String,
    onSeekMs: (Long) -> Unit,
) {
    val strings = LocalStrings.current
    if (transcript.isBlank()) {
        MarkdownPreview(transcript)
        return
    }
    val cueHeader = remember {
        Regex("""^\*\*(.+?)\*\*\s*·\s*(?:(\d+):)?(\d{1,2}):(\d{2})(?:[.,](\d{1,3}))?\s*$""")
    }
    val lines = remember(transcript) { transcript.lines() }
    val hasCues = remember(transcript) { lines.any { cueHeader.matches(it.trim()) } }
    if (!hasCues) {
        MarkdownPreview(transcript)
        return
    }
    Column(Modifier.fillMaxWidth()) {
        lines.forEach { raw ->
            val line = raw.trimEnd()
            val match = cueHeader.matchEntire(line.trim())
            if (match != null) {
                val hours = match.groupValues[2].toLongOrNull() ?: 0L
                val minutes = match.groupValues[3].toLongOrNull() ?: 0L
                val seconds = match.groupValues[4].toLongOrNull() ?: 0L
                val startMs = ((hours * 60L + minutes) * 60L + seconds) * 1000L
                Text(
                    transcriptHeaderDisplay(line, strings),
                    color = VelumInk,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 44.dp)
                        .clickable { onSeekMs(startMs) }
                        .padding(vertical = 8.dp),
                )
            } else if (line.isNotBlank()) {
                val isMarkersHeading = line.trim() == dev.velum.ai.pipeline.TranscriptFormatter.MARKERS_HEADING
                Text(
                    transcriptBodyLineDisplay(line, strings),
                    color = VelumInk,
                    fontSize = 15.sp,
                    fontWeight = if (isMarkersHeading) FontWeight.SemiBold else null,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            } else {
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

private fun formatSpeedLabel(speed: Float): String {
    val normalized = (speed * 100f).toInt() / 100f
    return when {
        normalized == 1.0f -> "1x"
        normalized == 0.75f -> "0.75x"
        normalized == 1.25f -> "1.25x"
        normalized == 1.5f -> "1.5x"
        normalized == 2.0f -> "2x"
        else -> "${normalized}x"
    }
}

@Composable
internal fun LiveTranscriptPane(
    container: AppContainer,
    recordingId: String,
    title: String,
    initialTranscript: String? = null,
    initialElapsedSeconds: Long = 0L,
    onStoppedAndQueued: () -> Unit = {},
    openTranscriber: (AppContainer) -> StreamingTranscriber? = ::openStreamingTranscriber,
    finishWaitMs: Long = LIVE_FINISH_WAIT_MS,
) {
    val strings = LocalStrings.current
    val seededTranscript = initialTranscript?.takeIf { it.isNotBlank() }
    var partial by remember(recordingId) {
        mutableStateOf(seededTranscript ?: "")
    }
    var usingMic by remember { mutableStateOf(false) }
    var ownedSession by remember(recordingId) { mutableStateOf<dev.velum.app.platform.MicSessionIdentity?>(null) }
    var pcmSubscription by remember(recordingId) { mutableStateOf<dev.velum.app.platform.MicPcmSubscription?>(null) }
    var stopping by remember { mutableStateOf(false) }
    // Kept as a kind (platform text aside) so a language change mid-recording re-translates it.
    var micErrorState by remember { mutableStateOf<MicError?>(null) }
    val micError: String? = micErrorState?.let {
        when (it) {
            MicError.StartFailed -> strings.micStartFailed
            MicError.Unsupported -> strings.micUnsupported
            MicError.Permission -> strings.micPermissionRationale
            is MicError.Platform -> it.message
        }
    }
    var inputSource by remember {
        val preferred = container.settingsRepository.preferences.value.defaultMicInput
        mutableStateOf(
            runCatching { MicInputSource.valueOf(preferred) }.getOrDefault(MicInputSource.Mic),
        )
    }
    var markerCount by remember { mutableStateOf(0) }
    // Mic already off (a Stop still finishing, or the notification): no model, no attach, and no false
    // error. The Stop button still finalizes it; the finalizer is safe against a Stop already in progress.
    val alreadyStopped = remember(recordingId) {
        container.activeMicRecordingId == recordingId && container.micCapture.sessions.value is dev.velum.app.platform.MicSessionState.Stopped
    }
    var pausedUi by remember { mutableStateOf(container.micCapture.isPaused || alreadyStopped) }
    val finished = remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // Capture only ever enqueues, so it never waits for the model or for decoding.
    // The queue drops from the capture thread. The flag is read at once by finishTranscript, so it is set right
    // here (the pane keeps no Compose state for it).
    val gapNow = remember { VolatileRef(false) }
    // `finished` and the latest partial as plain volatiles too: the capture thread and Stop's wait read them, and
    // reading Compose state off Main is what this pane avoids. They are written before the Main-side state is.
    val finishedNow = remember { VolatileRef(false) }
    val latestPartial = remember(recordingId) { VolatileRef(seededTranscript ?: "") }
    val pcmQueue = remember {
        Channel<ByteArray>(LIVE_PCM_BACKLOG_CHUNKS, BufferOverflow.DROP_OLDEST) {
            // One coroutine for the first drop only, not one per dropped chunk.
            if (!finishedNow.value && !gapNow.value) {
                gapNow.value = true
                container.micRecordings.publishLiveText(recordingId, null) // a hole in the text is never savable
            }
        }
    }
    val finalTranscript = remember { CompletableDeferred<String?>() }
    val wavPath = remember(recordingId) {
        "${recordingsDirectoryPath()}/$recordingId.wav"
    }
    // No usable streaming model: recording and the WAV go on, the pane says so instead of showing text.
    // Kept as a kind, not a string, so a language change mid-recording re-resolves it.
    var liveReady by remember(recordingId) { mutableStateOf(false) }
    var liveNotice by remember(recordingId) { mutableStateOf<LiveNotice?>(null) }
    val hasApiKey = container.settingsRepository.settings.collectAsState().value.hasApiKey
    val micSession by container.micCapture.sessions.collectAsState()
    // Seeded transcript (Roborazzi / resume) — keep it visible; keep the stored text.
    val preserveSeeded = seededTranscript != null

    // Screen state is written on Main only (the transcriber, the capture thread and Default workers call in from
    // elsewhere). On a real main looper the composition scope already resumes there; this keeps it true when a
    // collector is resumed inline on the writing thread, as the Compose test clock does (#345, #202).
    fun onMain(block: () -> Unit) {
        scope.launch(Dispatchers.Main.immediate) { block() }
    }
    // Cancelling the queue (Stop or leaving the screen) ends the transcriber coroutine below and drops
    // any backlog: decoding it would only delay Stop, and the queued recording is retranscribed from the WAV.
    // Marks the transcript finished and stops the queue; call it on Main, before awaiting the final text.
    fun markFinished() {
        finishedNow.value = true
        finished.value = true
        pcmQueue.cancel()
    }
    suspend fun awaitFinalText(): String? {
        // The fallback is the volatile copy: the Main-side state may lag a post, or never get it if the screen left.
        val text = withTimeoutOrNull(finishWaitMs) { finalTranscript.await() } ?: latestPartial.value
        // Text with a hole in the middle must not pass for the transcript if retranscribing fails.
        return if (gapNow.value) null else savableLiveTranscript(text)
    }

    // Opening a model is a blocking native load: on IO. NonCancellable because this coroutine
    // owns the native handle from open to finish, including when the screen leaves during the load.
    LaunchedEffect(Unit) {
        // A seeded transcript is never replaced by streaming, so no model is opened for it.
        if (preserveSeeded || alreadyStopped) {
            finalTranscript.complete(null)
            return@LaunchedEffect
        }
        withContext(NonCancellable + Dispatchers.IO) {
            finalTranscript.complete(
                transcribeQueuedPcm(
                    open = { openTranscriber(container) },
                    pcm = pcmQueue,
                    languageHint = container.settingsRepository.settings.value.audioLanguage,
                    onPartial = { md ->
                        latestPartial.value = md
                        // Published from here, not from an effect: with the screen off no frame is produced, so a
                        // recomposition-driven publish could leave a notification Stop with stale text.
                        container.micRecordings.publishLiveText(recordingId, if (gapNow.value) null else savableLiveTranscript(md))
                        // A drop between the check and the publish must not leave text with a hole in it published.
                        if (gapNow.value) container.micRecordings.publishLiveText(recordingId, null)
                        onMain { partial = md }
                    },
                    onReady = { onMain { liveReady = true } },
                    onNoTranscriber = { modelFailed ->
                        onMain { liveNotice = if (modelFailed) LiveNotice.Unavailable else LiveNotice.NoModel }
                    },
                ),
            )
        }
    }

    // Mic start is suspend (no main-thread runBlocking). Wire capture here.
    LaunchedEffect(recordingId, inputSource) {
        // The whole body runs on Main: the start suspends on a worker, and everything after it writes screen state
        // (a no-op on a real main looper; it holds when a test clock resumes the coroutine on the worker).
        withContext(Dispatchers.Main.immediate) {
            // Changing input source while FGS is already running does not retarget capture —
            // chips are disabled while usingMic; skip re-start.
            if (usingMic && container.micCapture.isCapturing) {
                return@withContext
            }
            val push: (ByteArray) -> Unit = { pcm ->
                if (!preserveSeeded) pcmQueue.trySend(pcm)
            }

            micErrorState = null
            if (!preserveSeeded || container.activeMicRecordingId == recordingId) {
                when (val started = container.micRecordings.startOrAttach(recordingId, wavPath, inputSource)) {
                    is MicStartResult.Started -> {
                        ownedSession = started.session
                        pcmSubscription = container.micRecordings.attach(recordingId, started.session, push)
                        usingMic = pcmSubscription != null
                        if (!usingMic && !alreadyStopped) micErrorState = MicError.StartFailed
                        markerCount = container.micCapture.markers.size
                        pausedUi = container.micCapture.isPaused || alreadyStopped
                    }
                    MicStartResult.Unsupported -> { usingMic = false; micErrorState = MicError.Unsupported }
                    MicStartResult.PermissionRequired -> { usingMic = false; micErrorState = MicError.Permission }
                    is MicStartResult.Failed -> { usingMic = false; micErrorState = started.message.takeIf { it.isNotBlank() }?.let { MicError.Platform(it) } ?: MicError.StartFailed }
                }
            } else {
                usingMic = false
                if (container.micCapture.isCapturing) micErrorState = MicError.StartFailed
            }
        }
    }
    // Seed the published live text with a resumed transcript; later text is published from onPartial.
    LaunchedEffect(recordingId) {
        if (seededTranscript != null) container.micRecordings.publishLiveText(recordingId, savableLiveTranscript(seededTranscript))
    }
    DisposableEffect(recordingId) {
        onDispose {
            pcmSubscription?.close()
            pcmSubscription = null
            finishedNow.value = true
            finished.value = true
            pcmQueue.cancel()
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
        if (preserveSeeded && !usingMic && micError == null) {
            Text(
                strings.ux.livePhoneMicFormat.replace("%d", markerCount.toString()),
                color = VelumInkSecondary,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            Row(
                Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlineButton(
                    if (pausedUi) strings.ux.liveResume else strings.ux.livePause,
                    { pausedUi = !pausedUi },
                    modifier = Modifier.weight(1f),
                )
                OutlineButton(
                    strings.ux.liveMark,
                    { markerCount += 1 },
                    modifier = Modifier.weight(1f),
                )
            }
        } else if (micError != null) {
            Text(
                micError!!,
                color = VelumInkSecondary,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        } else if (usingMic) {
            Text(
                strings.ux.liveMarkersFormat
                    .replace("%s", inputSource.localizedLabel(strings))
                    .replace("%d", markerCount.toString()),
                color = VelumInkSecondary,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
            Row(
                Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlineButton(
                    if (pausedUi) strings.ux.liveResume else strings.ux.livePause,
                    {
                        scope.launch(Dispatchers.Main.immediate) {
                            if (pausedUi) {
                                ownedSession?.let { container.micCapture.resume(it) }
                                pausedUi = false
                            } else {
                                ownedSession?.let { container.micCapture.pause(it) }
                                pausedUi = true
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
                OutlineButton(
                    strings.ux.liveMark,
                    {
                        scope.launch(Dispatchers.Main.immediate) {
                            when (ownedSession?.let { container.micCapture.addMarker(it) }) {
                                is MicMarkerResult.Ok -> {
                                    markerCount = container.micCapture.markers.size
                                    // Markers have no other copy: save now, not only at Stop (process death loses them).
                                    runCatching { persistLiveMarkers(container, recordingId) }
                                        .onFailure { println("VelumLivePcm: marker save failed: ${it::class.simpleName}") }
                                }
                                else -> Unit
                            }
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
                    // Source chips do not retarget a running FGS — disable while capturing.
                    OutlineButton(
                        src.localizedLabel(strings),
                        { inputSource = src },
                        modifier = Modifier,
                        enabled = !usingMic,
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
            val notice = liveNotice
            // "Recording continues" is only true while the mic really is capturing (a stop from the
            // notification ends the session without this screen's Stop); a mic error is shown above.
            val capturing = usingMic && micError == null && !stopping && micSession is dev.velum.app.platform.MicSessionState.Recording
            if (notice != null) {
                if (capturing) {
                    Text(
                        when (notice) {
                            LiveNotice.Unavailable -> strings.ux.liveModelUnavailable
                            LiveNotice.NoModel -> if (hasApiKey) strings.ux.liveNoModel else strings.ux.liveNoModelNoApi
                        },
                        color = VelumInkSecondary,
                        fontSize = 14.sp,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            } else if (savableLiveTranscript(partial) != null) {
                MarkdownPreview(partial)
            } else if (capturing && !liveReady) {
                // The model is still loading: say so rather than leave the pane blank.
                Text(
                    strings.ux.liveModelLoading,
                    color = VelumInkSecondary,
                    fontSize = 14.sp,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
        BlackButton(
            if (stopping) strings.generating else strings.stopRecording,
            {
                if (stopping) return@BlackButton
                val requested = ownedSession
                val owner = container.micRecordings.owner.value
                if (container.micCapture.isSupported && (requested == null || owner?.recordingId != recordingId || owner.session != requested)) {
                    micErrorState = MicError.StartFailed
                    return@BlackButton
                }
                stopping = true
                container.micRecordings.beginUiStop(recordingId)
                // Undispatched and NonCancellable: leaving the screen must not abandon a Stop in progress.
                scope.launch(Dispatchers.Main.immediate, start = CoroutineStart.UNDISPATCHED) { withContext(NonCancellable) {
                    val marks = container.micCapture.markers
                    // Stop means the mic is off now, before any wait. The platform keeps the terminal
                    // state, so the finalizer below picks up this same result.
                    if (container.micCapture.isSupported && requested != null) {
                        runCatching { container.micCapture.stop(requested) }
                            .onFailure { println("VelumLivePcm: stop failed: ${it::class.simpleName}") }
                    }
                    markFinished()
                    val finalMd = withContext(Dispatchers.Default) { awaitFinalText() }
                    when {
                        // The asynchronous transcript finish may outlive ownership. The finalizer
                        // rechecks the captured identity under its lock before any database write.
                        container.micCapture.isSupported -> {
                            when (val stopped = finalizeMicRecording(container, recordingId, wavPath, requested, transcript = finalMd, markers = marks)) {
                                is MicStopResult.Stopped -> Unit // Finalizer queued the verified audio.
                                is MicStopResult.Failed -> {
                                    usingMic = false
                                    micErrorState = stopped.message.takeIf { it.isNotBlank() }?.let { MicError.Platform(it) } ?: MicError.StartFailed
                                }
                                else -> Unit
                            }
                        }
                        else -> {
                            // No mic capture to finalize: mark failed.
                            container.repository.updateStatus(recordingId, "failed")
                        }
                    }
                    onStoppedAndQueued()
                } }
            },
            modifier = Modifier.padding(20.dp),
        )
    }
}

// ponytail: live preview only, the WAV never depends on it. At 40-130 ms per chunk this holds a slow
// model load (about 40 s or more); older audio is dropped from the preview past that.
internal const val LIVE_PCM_BACKLOG_CHUNKS = 1024

/** A value one thread sets and another reads at once; not Compose state, so it can be written from any thread. */
private class VolatileRef<T>(initial: T) {
    @kotlin.concurrent.Volatile var value: T = initial
}

private enum class LiveNotice { NoModel, Unavailable }

private sealed interface MicError {
    data object StartFailed : MicError
    data object Unsupported : MicError
    data object Permission : MicError
    data class Platform(val message: String) : MicError
}

// Stop must not hang on a stuck native load. The queued recording is retranscribed from the WAV anyway.
private const val LIVE_FINISH_WAIT_MS = 30_000L

private val LIVE_PARTIAL_HEADER = Regex("""^\*\*partial\*\* · [^\n]*\n?""")
private val LIVE_SPEAKER_HEADER = Regex("""\*\*Speaker \d+\*\* · \S+""")

/** Live text worth persisting, without the preview header; null when nothing was actually transcribed. */
internal fun savableLiveTranscript(markdown: String): String? {
    val text = LIVE_PARTIAL_HEADER.replaceFirst(markdown, "")
    return text.takeIf { body -> body.lineSequence().any { it.isNotBlank() && !LIVE_SPEAKER_HEADER.matches(it) } }
}

/**
 * Feeds queued PCM to a transcriber opened here and always finishes it once [pcm] closes.
 * Returns the final transcript, or null when no model could produce one (the WAV is unaffected).
 * Audio captured before the screen attaches never reaches this queue, only the WAV.
 */
private suspend fun transcribeQueuedPcm(
    open: () -> StreamingTranscriber?,
    pcm: ReceiveChannel<ByteArray>,
    languageHint: String?,
    onPartial: (String) -> Unit,
    /** Called once the transcriber has started and audio is being transcribed. */
    onReady: () -> Unit,
    /** Called when no live text can come: true when a model exists but failed, false when none is usable. */
    onNoTranscriber: (modelFailed: Boolean) -> Unit,
): String? {
    fun logFailure(step: String, failure: Throwable) = println("VelumLivePcm: $step failed: ${failure::class.simpleName}")
    val opened = runCatching(open).onFailure { logFailure("open", it) }
    val streamer = opened.getOrNull()
    if (streamer == null) {
        pcm.cancel() // nothing will read the backlog
        onNoTranscriber(opened.isFailure)
        return null
    }
    val started = runCatching {
        streamer.onPartial = onPartial
        streamer.start(16_000, languageHint, null)
    }.onFailure { logFailure("start", it) }.isSuccess
    if (started) onReady()
    if (!started) {
        pcm.cancel()
        onNoTranscriber(true)
    }
    while (started) {
        val chunk = pcm.receiveCatching().getOrNull() ?: break
        runCatching { streamer.pushPcm(chunk) }.onFailure { logFailure("push", it) }
    }
    return runCatching { streamer.finish() }.onFailure { logFailure("finish", it) }.getOrNull()
}

/**
 * Null when no streaming-capable model is Ready: the caller must show that, never a stand-in.
 * Throws when a Ready model exists but none could be started, so the pane can say "unavailable".
 */
private fun openStreamingTranscriber(container: AppContainer): StreamingTranscriber? {
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
    var readyButFailed = false
    for (modelId in preferredIds) {
        val path = container.localModelManager.modelPath(modelId)?.absolutePath ?: continue
        if (container.localModelManager.stateFor(modelId)?.status != LocalModelStatus.Ready) continue
        val created = runCatching { createStreamingTranscriber(path, modelId) }.getOrNull()
        if (created != null) return created
        readyButFailed = true
    }
    check(!readyButFailed) { "A streaming model is Ready but could not be started" }
    return null
}

@Composable
private fun NotesPane(
    summary: String?,
    generating: Boolean,
    processError: String? = null,
    onAskAi: () -> Unit,
    onGenerate: () -> Unit,
    onChangeTemplate: () -> Unit = {},
    onSummaryVersions: () -> Unit = {},
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
            when {
                generating -> {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        GeneratingBody()
                        AiGlowFooter(Modifier.align(Alignment.BottomCenter))
                    }
                }
                summary.isNullOrBlank() -> {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        EmptyState(
                            title = strings.notesEmptyTitle,
                            subtitle = strings.notesEmptyHint,
                            actionLabel = strings.generateCustom,
                            onAction = onGenerate,
                        )
                    }
                }
                else -> {
                    Column(
                        Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(20.dp),
                    ) {
                        MarkdownPreview(summary)
                        Spacer(Modifier.height(20.dp))
                        OutlineButton(strings.changeTemplate, onChangeTemplate)
                        Spacer(Modifier.height(12.dp))
                        OutlineButton(strings.summaryVersions, onSummaryVersions)
                        Spacer(Modifier.height(12.dp))
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
}

@Composable
private fun SummaryVersionsSheet(
    versions: List<Summary_version>,
    onActivate: (String) -> Unit,
    onDelete: (Summary_version) -> Unit,
    onClose: () -> Unit,
) {
    val strings = LocalStrings.current
    val now = remember { epochSecondsNow() }
    Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        Text(
            strings.summaryVersions,
            fontWeight = FontWeight.SemiBold,
            fontSize = 18.sp,
            color = VelumInk,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        if (versions.isEmpty()) {
            Text(
                strings.summaryVersionsEmpty,
                color = VelumInkSecondary,
                fontSize = 14.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(420.dp)
                    .padding(horizontal = 12.dp),
            ) {
                items(versions, key = { it.id }) { version ->
                    val active = version.is_active != 0L
                    val name = version.template_name.ifBlank {
                        version.template_id?.takeIf { it.isNotBlank() } ?: strings.template
                    }
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 10.dp)
                            .then(
                                if (active) {
                                    Modifier
                                        .border(1.dp, VelumBlack, RoundedCornerShape(8.dp))
                                        .padding(12.dp)
                                } else {
                                    Modifier.padding(horizontal = 4.dp)
                                },
                            ),
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    name,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 15.sp,
                                    color = VelumInk,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    relativeTimeLabel(version.created_at, now, strings.localeTag),
                                    color = VelumInkSecondary,
                                    fontSize = 12.sp,
                                )
                            }
                            if (active) {
                                Text(
                                    strings.versionActive,
                                    color = VelumInk,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!active) {
                                OutlineButton(
                                    text = strings.ux.restore,
                                    onClick = { onActivate(version.id) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            OutlineButton(
                                text = strings.ux.deleteForever,
                                onClick = { onDelete(version) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    ThinDivider()
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        TextButton(
            onClick = onClose,
            modifier = Modifier.padding(horizontal = 12.dp),
        ) {
            Text(strings.done)
        }
    }
}

private fun relativeTimeLabel(createdAt: Long, now: Long, localeTag: String): String {
    val delta = (now - createdAt).coerceAtLeast(0)
    val minutes = delta / 60
    val hours = minutes / 60
    val days = hours / 24
    val pt = localeTag.startsWith("pt", ignoreCase = true)
    return when {
        minutes < 1L -> if (pt) "agora" else "just now"
        minutes < 60L -> if (pt) "há ${minutes} min" else "${minutes}m ago"
        hours < 24L -> if (pt) "há ${hours} h" else "${hours}h ago"
        days < 7L -> if (pt) "há ${days} d" else "${days}d ago"
        else -> {
            val weeks = days / 7
            if (pt) "há ${weeks} sem" else "${weeks}w ago"
        }
    }
}

@Composable
private fun PlayerBar(
    durationLabel: String,
    enabled: Boolean = true,
    isPlaying: Boolean = false,
    speedLabel: String = "1x",
    skipSilence: Boolean = false,
    progress: Float = 0f,
    onPlayPause: () -> Unit = {},
    onSeekBack: () -> Unit = {},
    onSeekForward: () -> Unit = {},
    onCycleSpeed: () -> Unit = {},
    onToggleSkipSilence: () -> Unit = {},
    onAskAi: () -> Unit = {},
    onSeekFraction: (Float) -> Unit = {},
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
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .height(36.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFFF2F2F7))
                .semantics { contentDescription = strings.ux.waveformA11y }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectTapGestures { offset ->
                        val widthPx = size.width.coerceAtLeast(1)
                        onSeekFraction((offset.x / widthPx).coerceIn(0f, 1f))
                    }
                },
        ) {
            Box(
                Modifier
                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                    .height(36.dp)
                    .background(VelumInk.copy(alpha = 0.12f)),
            )
            Row(
                Modifier.fillMaxSize().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                repeat(48) { i ->
                    val h = 6 + (i % 7) * 3
                    val filled = i / 48f <= progress
                    Box(
                        Modifier
                            .width(3.dp)
                            .height(h.dp)
                            .background(
                                if (filled) VelumInk.copy(alpha = 0.7f)
                                else VelumInkSecondary.copy(alpha = 0.45f),
                                RoundedCornerShape(1.dp),
                            ),
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
                    .clickable(enabled = enabled, onClick = onPlayPause),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    contentDescription = if (isPlaying) strings.ux.pausePlayback else strings.play,
                    tint = tint,
                    modifier = Modifier.size(24.dp),
                )
            }
            Box(
                Modifier
                    .defaultMinSize(48.dp, 48.dp)
                    .clickable(enabled = enabled, onClick = onSeekBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Outlined.Undo,
                    contentDescription = strings.ux.seekBack15,
                    tint = tint,
                    modifier = Modifier.size(26.dp),
                )
            }
            Box(
                Modifier
                    .defaultMinSize(48.dp, 48.dp)
                    .clickable(enabled = enabled, onClick = onSeekForward),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Outlined.Redo,
                    contentDescription = strings.ux.seekForward15,
                    tint = tint,
                    modifier = Modifier.size(26.dp),
                )
            }
            Text(
                speedLabel,
                color = tint,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .defaultMinSize(48.dp, 48.dp)
                    .clickable(enabled = enabled, onClick = onCycleSpeed)
                    .padding(horizontal = 8.dp, vertical = 12.dp),
            )
            Text(
                strings.ux.skipSilence,
                color = if (skipSilence) VelumInk else tint,
                fontWeight = if (skipSilence) FontWeight.SemiBold else FontWeight.Medium,
                fontSize = 11.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .defaultMinSize(48.dp, 48.dp)
                    .width(56.dp)
                    .clickable(enabled = enabled, onClick = onToggleSkipSilence)
                    .padding(horizontal = 2.dp, vertical = 8.dp),
            )
            Box(
                Modifier
                    .defaultMinSize(48.dp, 48.dp)
                    .clickable(onClick = onAskAi),
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
    val rows = speakers
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
                            append(" · ")
                            append(formatFixed((it * 100).toDouble(), 0))
                            append('%')
                        }
                    },
                    color = VelumInkSecondary,
                    fontSize = 13.sp,
                )
            }
        }
        Text(
            if (rows.isEmpty()) strings.added.noSpeakers else strings.speakersUpdateNote,
            color = VelumInkSecondary,
            fontSize = 12.sp,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        )
        DualActionRow(strings.cancel, strings.confirm, onClose, { onConfirm(names) }, rightEnabled = rows.isNotEmpty())
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
    onApply: (templateId: String, language: String, autoLabel: Boolean, apiOverride: ApiModelOverride?) -> Unit =
        { _, _, _, _ -> onClose() },
    templates: List<String> = listOf("velum.meeting", "velum.general", "velum.it"),
    languages: List<String> = listOf("auto", "pt", "en", "es"),
    catalogTemplates: List<Template> = emptyList(),
    searchTemplateIds: (suspend (String) -> List<String>)? = null,
    profiles: List<ApiEndpointProfile> = emptyList(),
    modelsByProfile: Map<String, List<AiModelInfo>> = emptyMap(),
) {
    val strings = LocalStrings.current
    val languageTag = strings.localeTag
    var auto by remember { mutableStateOf(true) }
    var templateIdx by remember { mutableStateOf(0) }
    var selectedTemplateId by remember { mutableStateOf(templates.firstOrNull() ?: "velum.meeting") }
    var autoLabel by remember { mutableStateOf(true) }
    var langIdx by remember { mutableStateOf(0) }
    var pickerQuery by remember { mutableStateOf("") }
    var selectedTags by remember { mutableStateOf(setOf<String>()) }
    var selectedContentLang by remember { mutableStateOf<String?>(null) }
    var ftsIds by remember { mutableStateOf<List<String>?>(null) }
    var showAdvanced by remember { mutableStateOf(false) }
    var overrideProfileIdx by remember { mutableStateOf(0) }
    var overrideModelIdx by remember { mutableStateOf(0) }
    var useApiOverride by remember { mutableStateOf(false) }
    val templateLabels = templates.map { id ->
        BuiltinTemplateCatalog.get(id)?.displayName(strings.localeTag) ?: id
    }
    val useCatalogPicker = catalogTemplates.isNotEmpty()

    LaunchedEffect(pickerQuery, catalogTemplates, searchTemplateIds) {
        if (!useCatalogPicker || pickerQuery.isBlank() || searchTemplateIds == null) {
            ftsIds = null
            return@LaunchedEffect
        }
        val match = FtsQuery.fromUserInput(pickerQuery)
        ftsIds = if (match != null) {
            runCatching { searchTemplateIds(match) }.getOrNull()
        } else {
            null
        }
    }

    val filteredCatalog = remember(
        catalogTemplates,
        pickerQuery,
        ftsIds,
        selectedTags,
        selectedContentLang,
    ) {
        if (!useCatalogPicker) return@remember emptyList()
        val searched = RecordingSearch.templates(catalogTemplates, pickerQuery, ftsIds)
        val rows = searched.map {
            TemplateTagFilter.Row(
                id = it.id,
                tags = TemplateTagVocabulary.decode(it.tags),
                lang = it.lang,
                category = it.category,
            )
        }
        val kept = TemplateTagFilter.filter(
            items = rows,
            selectedTags = selectedTags,
            selectedLang = selectedContentLang,
        ).map { it.id }.toSet()
        searched.filter { it.id in kept }
    }
    val pickerTags = remember(catalogTemplates) {
        TemplateTagFilter.tagsPresentIn(
            catalogTemplates.map {
                TemplateTagFilter.Row(it.id, TemplateTagVocabulary.decode(it.tags), it.lang, it.category)
            },
        )
    }
    val pickerLangs = remember(catalogTemplates) {
        TemplateTagFilter.langsPresentIn(
            catalogTemplates.map {
                TemplateTagFilter.Row(it.id, TemplateTagVocabulary.decode(it.tags), it.lang, it.category)
            },
        ).filter { it != "und" }
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
            if (useCatalogPicker) {
                BasicTextField(
                    value = pickerQuery,
                    onValueChange = { pickerQuery = it },
                    textStyle = TextStyle(color = VelumInk, fontSize = 14.sp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .border(1.dp, VelumDivider, RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    decorationBox = { inner ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Search, null, tint = VelumInkSecondary, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Box(Modifier.weight(1f)) {
                                if (pickerQuery.isEmpty()) {
                                    Text(strings.template, color = VelumInkSecondary, fontSize = 14.sp)
                                }
                                inner()
                            }
                        }
                    },
                )
                if (pickerTags.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        pickerTags.take(16).forEach { tag ->
                            ModeChip(
                                TemplateTagVocabulary.label(tag, languageTag),
                                tag in selectedTags,
                            ) {
                                selectedTags =
                                    if (tag in selectedTags) selectedTags - tag else selectedTags + tag
                            }
                        }
                    }
                }
                if (pickerLangs.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ModeChip(
                            strings.templatesAll,
                            selectedContentLang == null,
                        ) { selectedContentLang = null }
                        pickerLangs.forEach { lang ->
                            ModeChip(lang.uppercase(), selectedContentLang == lang) {
                                selectedContentLang = lang
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                LazyColumn(
                    Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .padding(horizontal = 12.dp),
                ) {
                    items(filteredCatalog, key = { it.id }) { tpl ->
                        val selected = tpl.id == selectedTemplateId
                        val name = BuiltinTemplateCatalog.get(tpl.id)?.displayName(languageTag) ?: tpl.name
                        val tagLine = TemplateTagVocabulary.decode(tpl.tags)
                            .take(3)
                            .joinToString(" · ") { TemplateTagVocabulary.label(it, languageTag) }
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (selected) VelumBlack else Color.Transparent)
                                .clickable { selectedTemplateId = tpl.id }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                        ) {
                            Text(
                                name,
                                color = if (selected) VelumOnBlack else VelumInk,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (tagLine.isNotEmpty()) {
                                Text(
                                    tagLine,
                                    color = if (selected) VelumOnBlack.copy(alpha = 0.75f) else VelumInkSecondary,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            } else {
                ChevronRow(
                    strings.template,
                    {
                        templateIdx = (templateIdx + 1) % templates.size.coerceAtLeast(1)
                    },
                    value = templateLabels.getOrElse(templateIdx) { templates[templateIdx] },
                )
            }
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
            showDivider = profiles.isEmpty(),
        )
        if (profiles.isNotEmpty()) {
            ChevronRow(
                strings.settingsUi.generateAdvancedModel,
                { showAdvanced = !showAdvanced },
                value = if (showAdvanced) strings.on else strings.off,
                showDivider = !showAdvanced,
            )
            if (showAdvanced) {
                ChevronRow(
                    strings.settingsUi.useModelOverride,
                    { useApiOverride = !useApiOverride },
                    value = if (useApiOverride) strings.on else strings.off,
                )
                if (useApiOverride) {
                    val profile = profiles.getOrElse(overrideProfileIdx) { profiles.first() }
                    val models = modelsByProfile[profile.id].orEmpty()
                    ChevronRow(
                        strings.settingsUi.endpointProfile,
                        {
                            overrideProfileIdx = (overrideProfileIdx + 1) % profiles.size
                            overrideModelIdx = 0
                        },
                        value = profile.name,
                    )
                    ChevronRow(
                        strings.settingsUi.modelForSummaries,
                        {
                            if (models.isNotEmpty()) {
                                overrideModelIdx = (overrideModelIdx + 1) % models.size
                            }
                        },
                        value = models.getOrNull(overrideModelIdx)?.id
                            ?: strings.settingsUi.modelManualId,
                        showDivider = false,
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        BlackButton(
            strings.apply,
            {
                val templateId = if (auto) {
                    "velum.meeting"
                } else if (useCatalogPicker) {
                    selectedTemplateId
                } else {
                    templates.getOrElse(templateIdx) { "velum.meeting" }
                }
                val override = if (useApiOverride && profiles.isNotEmpty()) {
                    val profile = profiles.getOrElse(overrideProfileIdx) { profiles.first() }
                    val model = modelsByProfile[profile.id].orEmpty()
                        .getOrNull(overrideModelIdx)?.id
                    ApiModelOverride(
                        profileId = profile.id,
                        modelId = model,
                    )
                } else {
                    null
                }
                onApply(templateId, languages[langIdx], autoLabel, override)
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
