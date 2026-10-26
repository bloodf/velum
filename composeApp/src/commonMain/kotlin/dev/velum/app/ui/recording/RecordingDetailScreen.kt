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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.velum.app.di.AppContainer
import dev.velum.app.i18n.LocalStrings
import dev.velum.app.ui.components.AiGlowFooter
import dev.velum.app.ui.components.BlackButton
import dev.velum.app.ui.components.ChevronRow
import dev.velum.app.ui.components.DualActionRow
import dev.velum.app.ui.components.OutlineButton
import dev.velum.app.ui.theme.VelumBg
import dev.velum.app.ui.theme.VelumBlack
import dev.velum.app.ui.theme.VelumDivider
import dev.velum.app.ui.theme.VelumInk
import dev.velum.app.ui.theme.VelumInkSecondary
import dev.velum.app.ui.theme.VelumSurface

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingDetailScreen(
    container: AppContainer,
    recordingId: String,
    onBack: () -> Unit,
    initialTab: Int = 0,
    showSpeakersInitially: Boolean = false,
    showGenerateInitially: Boolean = false,
) {
    val strings = LocalStrings.current
    val recording by container.repository.observeRecording(recordingId).collectAsState(null)
    var tab by remember { mutableStateOf(initialTab) } // 0 fontes, 1 notas
    var showMenu by remember { mutableStateOf(false) }
    var showSpeakers by remember { mutableStateOf(showSpeakersInitially) }
    var showGenerate by remember { mutableStateOf(showGenerateInitially) }
    val generating = recording?.status == "generating" || recording?.summary_markdown.isNullOrBlank()

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
            Icon(
                Icons.AutoMirrored.Outlined.ArrowBack,
                null,
                tint = VelumInk,
                modifier = Modifier
                    .size(40.dp)
                    .clickable(onClick = onBack)
                    .padding(8.dp),
            )
            Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.Center) {
                TabLabel(strings.sources, selected = tab == 0) { tab = 0 }
                Spacer(Modifier.width(28.dp))
                TabLabel(strings.notes, selected = tab == 1) { tab = 1 }
            }
            Icon(
                Icons.Outlined.IosShare,
                strings.share,
                tint = VelumInk,
                modifier = Modifier.size(22.dp).clickable { },
            )
            Spacer(Modifier.width(12.dp))
            Box {
                Icon(
                    Icons.Outlined.MoreHoriz,
                    strings.more,
                    tint = VelumInk,
                    modifier = Modifier.size(22.dp).clickable { showMenu = true },
                )
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem({ Text(strings.moveToFolder) }, { showMenu = false })
                    DropdownMenuItem({ Text(strings.findReplace) }, { showMenu = false })
                    DropdownMenuItem({ Text(strings.retranscribe) }, {
                        showMenu = false
                        showGenerate = true
                    })
                    DropdownMenuItem({ Text(strings.nameSpeakers) }, {
                        showMenu = false
                        showSpeakers = true
                    })
                    DropdownMenuItem({ Text(strings.moveToTrash) }, {
                        showMenu = false
                        // fire-and-forget via rememberCoroutineScope would be better; keep simple
                    })
                }
            }
            Spacer(Modifier.width(8.dp))
        }

        if (tab == 0) {
            SourcesPane(
                transcript = recording?.transcript,
                durationSeconds = recording?.duration_seconds ?: 0,
                generating = generating && recording?.transcript.isNullOrBlank(),
                onGenerate = { showGenerate = true },
            )
        } else {
            NotesPane(
                summary = recording?.summary_markdown,
                generating = generating,
                onAskAi = { },
                onGenerate = { showGenerate = true },
            )
        }
    }

    if (showSpeakers) {
        ModalBottomSheet(
            onDismissRequest = { showSpeakers = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = VelumSurface,
        ) {
            SpeakerLabelSheetContent(onClose = { showSpeakers = false })
        }
    }
    if (showGenerate) {
        ModalBottomSheet(
            onDismissRequest = { showGenerate = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = VelumSurface,
        ) {
            GenerateSheetContent(onClose = { showGenerate = false })
        }
    }
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
    generating: Boolean,
    onGenerate: () -> Unit,
) {
    val strings = LocalStrings.current
    val dur = formatHms(durationSeconds)
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
        PlayerBar(durationLabel = "00:00:00 / $dur")
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
                    Text(
                        transcript.orEmpty().ifBlank { "—" },
                        color = VelumInk,
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
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

@Composable
private fun NotesPane(
    summary: String?,
    generating: Boolean,
    onAskAi: () -> Unit,
    onGenerate: () -> Unit,
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
                    Text(summary, color = VelumInk, fontSize = 15.sp, lineHeight = 22.sp)
                    Spacer(Modifier.height(20.dp))
                    OutlineButton(strings.mindMap, {})
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
private fun PlayerBar(durationLabel: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
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
                .background(Color(0xFFF2F2F7)),
        ) {
            // simple waveform bars
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
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFF2F2F7)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.PlayArrow, null, tint = VelumInk)
            }
            Icon(Icons.AutoMirrored.Outlined.Undo, "−15", tint = VelumInk, modifier = Modifier.size(26.dp))
            Icon(Icons.AutoMirrored.Outlined.Redo, "+15", tint = VelumInk, modifier = Modifier.size(26.dp))
            Text("1x", color = VelumInk, fontWeight = FontWeight.Medium)
            Icon(Icons.Outlined.AutoAwesome, null, tint = VelumInk, modifier = Modifier.size(22.dp))
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

@Composable
fun SpeakerLabelSheetContent(onClose: () -> Unit) {
    val strings = LocalStrings.current
    val demos = listOf(
        "Speaker 1" to "5m 12s",
        "Speaker 2" to "3m 40s",
    )
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(strings.nameSpeakers, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            Text("×", fontSize = 22.sp, color = VelumInkSecondary, modifier = Modifier.clickable(onClick = onClose))
        }
        demos.forEach { (name, dur) ->
            Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .border(1.dp, VelumDivider, RoundedCornerShape(8.dp))
                        .padding(14.dp),
                ) {
                    Text(name, color = VelumInkSecondary, fontSize = 15.sp)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "${strings.totalDuration}: ",
                    color = VelumInk,
                    fontSize = 14.sp,
                )
                Text(dur, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = VelumInk)
            }
        }
        Text(
            strings.speakersUpdateNote,
            color = VelumInkSecondary,
            fontSize = 12.sp,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        )
        DualActionRow(strings.cancel, strings.confirm, onClose, onClose)
    }
}

@Composable
fun GenerateSheetContent(onClose: () -> Unit) {
    val strings = LocalStrings.current
    var auto by remember { mutableStateOf(true) }
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
        ChevronRow(strings.template, {}, value = strings.meetingHighlights)
        ChevronRow(strings.autoLabelVoice, {}, value = strings.on)
        ChevronRow(strings.audioLanguage, {}, value = strings.automatic)
        ChevronRow(strings.aiModel, {}, value = strings.automatic, showDivider = false)
        Spacer(Modifier.height(12.dp))
        BlackButton(strings.apply, onClose, modifier = Modifier.padding(horizontal = 20.dp))
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
        Text(text, color = if (selected) Color.White else VelumInk, fontSize = 13.sp)
    }
}

private fun formatHms(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return "%02d:%02d:%02d".format(h, m, s)
}
