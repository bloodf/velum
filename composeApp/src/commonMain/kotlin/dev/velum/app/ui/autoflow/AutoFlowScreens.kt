package dev.velum.app.ui.autoflow

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.velum.app.di.AppContainer
import dev.velum.app.i18n.LocalStrings
import dev.velum.app.i18n.Strings
import dev.velum.app.ui.components.DualActionRow
import dev.velum.app.ui.components.EmptyState
import dev.velum.app.ui.components.MetaRow
import dev.velum.app.ui.components.OutlineButton
import dev.velum.app.ui.components.SmallEditPill
import dev.velum.app.ui.components.ThinDivider
import dev.velum.app.ui.components.ToggleRow
import dev.velum.app.ui.theme.VelumBg
import dev.velum.app.ui.theme.VelumBlack
import dev.velum.app.ui.theme.VelumDivider
import dev.velum.app.ui.theme.VelumInk
import dev.velum.app.ui.theme.VelumInkSecondary
import dev.velum.app.ui.theme.VelumOnBlack
import dev.velum.app.ui.theme.VelumSurface
import dev.velum.core.autoflow.AutoFlowPostAction
import dev.velum.core.data.epochSecondsNow
import kotlinx.coroutines.launch

@Composable
fun AutoFlowListScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
) {
    val strings = LocalStrings.current
    val flows by container.repository.observeAutoflows().collectAsState(emptyList())
    val prefs by container.settingsRepository.preferences.collectAsState()
    val scope = rememberCoroutineScope()

    Column(
        Modifier
            .fillMaxSize()
            .background(VelumBg)
            .verticalScroll(rememberScrollState()),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.AutoMirrored.Outlined.ArrowBack,
                null,
                tint = VelumInk,
                modifier = Modifier.size(40.dp).clickable(onClick = onBack).padding(8.dp),
            )
            Spacer(Modifier.weight(1f))
            Box(
                Modifier
                    .defaultMinSize(48.dp, 48.dp)
                    .size(48.dp)
                    .border(1.dp, VelumDivider, RoundedCornerShape(8.dp))
                    .clickable { onEdit("new") },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Add, strings.add, tint = VelumInk)
            }
            Spacer(Modifier.width(8.dp))
        }
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier
                .size(64.dp)
                .align(Alignment.CenterHorizontally)
                .clip(RoundedCornerShape(14.dp))
                .background(VelumSurface)
                .border(1.dp, VelumDivider, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.AccountTree, null, tint = VelumInk, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.height(12.dp))
        Text(
            strings.autoFlow,
            fontWeight = FontWeight.Thin,
            fontSize = 32.sp,
            color = VelumInk,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            strings.autoFlowSubtitle,
            color = VelumInkSecondary,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 28.dp),
        )
        Spacer(Modifier.height(20.dp))
        ThinDivider(0)
        ToggleRow(
            strings.receiveNotifications,
            prefs.autoflowNotify,
            { enabled ->
                scope.launch {
                    container.settingsRepository.setAutoflowNotify(enabled)
                    flows.forEach { flow ->
                        container.repository.setAutoflowNotify(flow.id, enabled)
                    }
                }
            },
        )
        if (flows.isEmpty()) {
            Spacer(Modifier.height(32.dp))
            EmptyState(
                title = strings.ux.autoFlowEmpty,
                subtitle = strings.ux.autoFlowEmptyHint,
                actionLabel = strings.create,
                onAction = { onEdit("new") },
            )
        } else {
            flows.forEach { flow ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onEdit(flow.id) }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Mic, null, tint = VelumInkSecondary, modifier = Modifier.size(16.dp))
                            Text(" › ", color = VelumInkSecondary)
                            Icon(Icons.Outlined.AutoAwesome, null, tint = VelumInkSecondary, modifier = Modifier.size(16.dp))
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(flow.name, color = VelumInk, fontSize = 15.sp)
                    }
                    androidx.compose.material3.Switch(
                        checked = flow.enabled == 1L,
                        onCheckedChange = { enabled ->
                            scope.launch { container.repository.setAutoflowEnabled(flow.id, enabled) }
                        },
                    )
                }
                ThinDivider(20)
            }
        }
        Spacer(Modifier.height(40.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoFlowEditScreen(
    container: AppContainer,
    flowId: String,
    onBack: () -> Unit,
) {
    val strings = LocalStrings.current
    val flows by container.repository.observeAutoflows().collectAsState(emptyList())
    val templates by container.repository.observeTemplates().collectAsState(emptyList())
    val isNew = flowId == "new"
    val flow = flows.firstOrNull { it.id == flowId }
    var enabled by remember(flow, isNew) { mutableStateOf(flow?.enabled == 1L || isNew) }
    var name by remember(flow, isNew) { mutableStateOf(flow?.name ?: strings.newAutoFlow) }
    var wakePhrase by remember(flow) { mutableStateOf(flow?.wake_phrase.orEmpty()) }
    var sourceFilter by remember(flow) { mutableStateOf(flow?.source_filter ?: "note_pro") }
    var templateId by remember(flow) {
        mutableStateOf(flow?.template_id ?: "velum.meeting")
    }
    var language by remember(flow) { mutableStateOf(flow?.language ?: "auto") }
    var autoLabel by remember(flow) { mutableStateOf(flow?.auto_label_voice == 1L) }
    var notify by remember(flow, isNew) { mutableStateOf(flow?.notify != 0L) }
    var durationMin by remember(flow) { mutableStateOf(flow?.duration_min_seconds ?: 0L) }
    var durationMax by remember(flow) { mutableStateOf(flow?.duration_max_seconds ?: 10800L) }
    var durationMinText by remember(flow) {
        mutableStateOf(((flow?.duration_min_seconds ?: 0L) / 60L).toString())
    }
    var durationMaxText by remember(flow) {
        mutableStateOf(((flow?.duration_max_seconds ?: 10800L) / 3600L).toString())
    }
    var postAction by remember(flow) { mutableStateOf(flow?.post_action.orEmpty()) }
    var dirty by remember { mutableStateOf(false) }
    var showDiscard by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var editWhen by remember { mutableStateOf(false) }
    var editDo by remember { mutableStateOf(false) }
    var postActionChoices by remember {
        mutableStateOf(
            listOf(
                "" to strings.postActionNone,
                "share" to strings.postActionShare,
                "calendar_insert" to strings.postActionCalendar,
            ),
        )
    }
    val scope = rememberCoroutineScope()

    LaunchedEffect(container, editDo, strings) {
        val actions = container.integrationRegistry.availablePostActions()
        postActionChoices = actions.map { action ->
            when (action) {
                AutoFlowPostAction.None -> "" to strings.postActionNone
                AutoFlowPostAction.ShareMarkdown -> "share" to strings.postActionShare
                AutoFlowPostAction.CalendarInsert ->
                    "calendar_insert" to strings.postActionCalendar
                AutoFlowPostAction.FolderExport ->
                    "folder_export" to strings.postActionFolderExport
                AutoFlowPostAction.Webhook -> "webhook" to strings.postActionWebhook
            }
        }
    }

    fun markDirty() {
        dirty = true
    }

    fun tryBack() {
        if (dirty) showDiscard = true else onBack()
    }

    suspend fun persist() {
        val id = flow?.id ?: "flow-${epochSecondsNow()}-${(1000..9999).random()}"
        container.repository.upsertAutoflow(
            id = id,
            name = name,
            enabled = enabled,
            wakePhrase = wakePhrase.ifBlank { null },
            durationMinSeconds = durationMin,
            durationMaxSeconds = durationMax,
            sourceFilter = sourceFilter,
            templateId = templateId,
            autoLabelVoice = autoLabel,
            language = language,
            notify = notify,
            postAction = postAction.ifBlank { null },
        )
    }

    val templateName = templates.firstOrNull { it.id == templateId }?.name
        ?: strings.meetingHighlights
    val languageLabel = when (language) {
        "auto" -> strings.automatic
        "pt-BR" -> strings.languagePtBr
        "en" -> strings.languageEn
        else -> language
    }
    val wakeLabel = wakePhrase.ifBlank { strings.postActionNone }
    val sourceLabel = sourceFilterLabel(sourceFilter, strings)

    Column(
        Modifier
            .fillMaxSize()
            .background(VelumSurface)
            .verticalScroll(rememberScrollState()),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.AutoMirrored.Outlined.ArrowBack,
                null,
                tint = VelumInk,
                modifier = Modifier.size(40.dp).clickable { tryBack() }.padding(8.dp),
            )
            Spacer(Modifier.weight(1f))
            Text(
                strings.save,
                color = VelumInk,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                modifier = Modifier
                    .padding(end = 12.dp)
                    .clickable {
                        scope.launch {
                            persist()
                            dirty = false
                            onBack()
                        }
                    },
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(strings.name, color = VelumInk, fontSize = 16.sp)
            BasicTextField(
                value = name,
                onValueChange = {
                    name = it
                    markDirty()
                },
                singleLine = true,
                cursorBrush = SolidColor(VelumInk),
                textStyle = TextStyle(color = VelumInkSecondary, fontSize = 16.sp),
                modifier = Modifier.width(200.dp),
            )
        }
        ToggleRow(strings.activate + " AutoFlow", enabled, {
            enabled = it
            markDirty()
        })
        ToggleRow(strings.receiveNotifications, notify, {
            notify = it
            markDirty()
        })
        ThinDivider(0)
        Spacer(Modifier.height(12.dp))
        Text(
            strings.whenSection,
            fontWeight = FontWeight.SemiBold,
            fontSize = 17.sp,
            color = VelumInk,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Mic, null, tint = VelumInk)
            SmallEditPill(strings.edit) { editWhen = true }
        }
        Text(strings.whenNewRecording, modifier = Modifier.padding(horizontal = 20.dp), fontSize = 15.sp, color = VelumInk)
        Text(
            strings.whenNewRecordingHint,
            color = VelumInkSecondary,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        )
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            MetaRow(strings.wakeWord, wakeLabel)
            MetaRow(
                strings.recordingDuration,
                "${durationMin / 60} min - ${durationMax / 3600} h",
            )
            MetaRow(strings.recordingSource, sourceLabel)
        }
        ThinDivider(0)
        Spacer(Modifier.height(12.dp))
        Text(
            strings.doSection,
            fontWeight = FontWeight.SemiBold,
            fontSize = 17.sp,
            color = VelumInk,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.AutoAwesome, null, tint = VelumInk)
            SmallEditPill(strings.edit) { editDo = true }
        }
        Text(strings.doTranscribeSummarize, modifier = Modifier.padding(horizontal = 20.dp), fontSize = 15.sp, color = VelumInk)
        Text(
            strings.doTranscribeSummarizeHint,
            color = VelumInkSecondary,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        )
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            MetaRow(strings.summaryTemplate, templateName)
            MetaRow(strings.autoSpeakerLabel, if (autoLabel) strings.on else strings.off)
            MetaRow(strings.recordingLanguage, languageLabel)
            MetaRow(
                strings.postActionLabel,
                postActionChoices.firstOrNull { it.first == postAction }?.second
                    ?: strings.postActionNone,
            )
        }
        if (!isNew && flow != null) {
            Spacer(Modifier.height(24.dp))
            OutlineButton(
                strings.delete,
                { showDeleteConfirm = true },
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
            )
        }
        Spacer(Modifier.height(40.dp))
    }

    if (editWhen) {
        ModalBottomSheet(
            onDismissRequest = { editWhen = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = VelumSurface,
        ) {
            Column(Modifier.padding(vertical = 8.dp)) {
                Text(
                    strings.whenSection,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp,
                    color = VelumInk,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    strings.wakeWord,
                    color = VelumInkSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                BasicTextField(
                    value = wakePhrase,
                    onValueChange = {
                        wakePhrase = it
                        markDirty()
                    },
                    singleLine = true,
                    cursorBrush = SolidColor(VelumInk),
                    textStyle = TextStyle(color = VelumInk, fontSize = 16.sp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                )
                ThinDivider(0)
                Spacer(Modifier.height(8.dp))
                Text(
                    strings.ux.durationMinMinutes,
                    color = VelumInkSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                BasicTextField(
                    value = durationMinText,
                    onValueChange = { raw ->
                        durationMinText = raw.filter { it.isDigit() }.take(5)
                        durationMin = (durationMinText.toLongOrNull() ?: 0L) * 60L
                        markDirty()
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    cursorBrush = SolidColor(VelumInk),
                    textStyle = TextStyle(color = VelumInk, fontSize = 16.sp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                )
                Text(
                    strings.ux.durationMaxHours,
                    color = VelumInkSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                BasicTextField(
                    value = durationMaxText,
                    onValueChange = { raw ->
                        durationMaxText = raw.filter { it.isDigit() }.take(3)
                        val hours = durationMaxText.toLongOrNull() ?: 3L
                        durationMax = (hours.coerceAtLeast(1L)) * 3600L
                        markDirty()
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    cursorBrush = SolidColor(VelumInk),
                    textStyle = TextStyle(color = VelumInk, fontSize = 16.sp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                )
                ThinDivider(0)
                Spacer(Modifier.height(8.dp))
                Text(
                    strings.recordingSource,
                    color = VelumInkSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                listOf("note_pro", "phone", "import", "all").forEach { src ->
                    SelectableSheetRow(
                        label = sourceFilterLabel(src, strings),
                        selected = sourceFilter == src,
                        onClick = {
                            sourceFilter = src
                            markDirty()
                        },
                    )
                }
                DualActionRow(strings.cancel, strings.done, { editWhen = false }, { editWhen = false })
            }
        }
    }

    if (editDo) {
        ModalBottomSheet(
            onDismissRequest = { editDo = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = VelumSurface,
        ) {
            Column(Modifier.padding(vertical = 8.dp)) {
                Text(
                    strings.doSection,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp,
                    color = VelumInk,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    strings.summaryTemplate,
                    color = VelumInkSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                templates.take(8).forEach { tpl ->
                    SelectableSheetRow(
                        label = tpl.name,
                        selected = templateId == tpl.id,
                        onClick = {
                            templateId = tpl.id
                            markDirty()
                        },
                    )
                }
                ToggleRow(strings.autoSpeakerLabel, autoLabel, {
                    autoLabel = it
                    markDirty()
                })
                Spacer(Modifier.height(8.dp))
                Text(
                    strings.recordingLanguage,
                    color = VelumInkSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                listOf(
                    "auto" to strings.automatic,
                    "pt-BR" to strings.languagePtBr,
                    "en" to strings.languageEn,
                ).forEach { (value, label) ->
                    SelectableSheetRow(
                        label = label,
                        selected = language == value,
                        onClick = {
                            language = value
                            markDirty()
                        },
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    strings.postActionLabel,
                    color = VelumInkSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                postActionChoices.forEach { (value, label) ->
                    SelectableSheetRow(
                        label = label,
                        selected = postAction == value,
                        onClick = {
                            postAction = value
                            markDirty()
                        },
                    )
                }
                DualActionRow(strings.cancel, strings.done, { editDo = false }, { editDo = false })
            }
        }
    }

    if (showDiscard) {
        ModalBottomSheet(
            onDismissRequest = { showDiscard = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = VelumSurface,
        ) {
            Column(Modifier.padding(20.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(strings.unsavedChanges, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                    Icon(Icons.Outlined.Close, null, modifier = Modifier.clickable { showDiscard = false })
                }
                Spacer(Modifier.height(8.dp))
                Text(strings.unsavedChangesBody, color = VelumInkSecondary, fontSize = 14.sp)
                DualActionRow(strings.discard, strings.save, {
                    showDiscard = false
                    onBack()
                }, {
                    showDiscard = false
                    dirty = false
                    scope.launch {
                        persist()
                        onBack()
                    }
                })
            }
        }
    }

    if (showDeleteConfirm && flow != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(strings.ux.confirmDeleteTitle) },
            text = { Text(strings.ux.confirmDeleteAutoFlowBody) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        scope.launch {
                            container.repository.deleteAutoflow(flow.id)
                            onBack()
                        }
                    },
                ) { Text(strings.delete) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text(strings.cancel) }
            },
        )
    }
}

@Composable
private fun SelectableSheetRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            color = if (selected) VelumInk else VelumInkSecondary,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            fontSize = 16.sp,
        )
        Box(
            Modifier
                .size(22.dp)
                .clip(CircleShape)
                .border(1.5.dp, if (selected) VelumBlack else VelumDivider, CircleShape)
                .background(if (selected) VelumBlack else VelumSurface),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(
                    Icons.Outlined.Check,
                    contentDescription = null,
                    tint = VelumOnBlack,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

private fun sourceFilterLabel(src: String, strings: Strings): String = when (src) {
    "note_pro" -> strings.ux.sourceNotePro
    "phone" -> strings.ux.sourcePhone
    "import" -> strings.ux.sourceImport
    "all" -> strings.everything
    else -> src
}
