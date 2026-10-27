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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.velum.app.di.AppContainer
import dev.velum.app.i18n.LocalStrings
import dev.velum.app.ui.components.BlackButton
import dev.velum.app.ui.components.ChevronRow
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
import dev.velum.core.model.AiModelInfo
import dev.velum.core.model.ComputeMode

/** OpenAI-compatible BYO API — no account / login / cloud membership. */
@Composable
fun ApiSettingsScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onAiProcessing: () -> Unit = {},
    onModelManager: () -> Unit = {},
) {
    val strings = LocalStrings.current
    val viewModel = container.settingsViewModel
    val settings by viewModel.settings.collectAsState()
    val ui by viewModel.ui.collectAsState()

    var baseUrl by remember(settings.baseUrl) { mutableStateOf(settings.baseUrl) }
    var apiKey by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var modelQuery by remember { mutableStateOf("") }
    var manualTranscription by remember(settings.transcriptionModel) {
        mutableStateOf(settings.transcriptionModel.orEmpty())
    }
    var manualDiarization by remember(settings.diarizationModel) {
        mutableStateOf(settings.diarizationModel.orEmpty())
    }
    var manualChat by remember(settings.chatModel) {
        mutableStateOf(settings.chatModel.orEmpty())
    }
    var manualEmbedding by remember(settings.embeddingModel) {
        mutableStateOf(settings.embeddingModel.orEmpty())
    }

    val listingFailed = ui.error != null && !ui.refreshing
    val filtered = remember(settings.cachedModels, modelQuery) {
        val q = modelQuery.trim().lowercase()
        if (q.isEmpty()) settings.cachedModels
        else settings.cachedModels.filter {
            it.id.lowercase().contains(q) || (it.ownedBy?.lowercase()?.contains(q) == true)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(VelumBg)
            .verticalScroll(rememberScrollState()),
    ) {
        ScreenTopBar(strings.aiProvider, onBack = onBack)
        Spacer(Modifier.height(8.dp))
        ChevronRow(strings.aiProcessing, onAiProcessing)
        ChevronRow(strings.modelManager, onModelManager, showDivider = false)
        Spacer(Modifier.height(16.dp))
        Text(
            strings.settingsUi.apiHint,
            color = VelumInkSecondary,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            label = { Text(strings.baseUrl) },
            singleLine = true,
            shape = RoundedCornerShape(10.dp),
        )
        Spacer(Modifier.height(8.dp))
        BlackButton(strings.save, { viewModel.setBaseUrl(baseUrl) }, Modifier.padding(horizontal = 20.dp))
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            label = {
                Text(
                    if (settings.hasApiKey) "${strings.apiKey} (${strings.enabled})" else strings.apiKey,
                )
            },
            singleLine = true,
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                Text(
                    if (showKey) strings.settingsUi.hide else strings.settingsUi.show,
                    modifier = Modifier.padding(end = 8.dp).clickable { showKey = !showKey },
                    color = VelumInkSecondary,
                )
            },
            shape = RoundedCornerShape(10.dp),
        )
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BlackButton(
                strings.save,
                {
                    viewModel.setApiKey(apiKey)
                    apiKey = ""
                    viewModel.refreshModels()
                },
                Modifier.weight(1f),
                enabled = apiKey.isNotBlank(),
            )
            OutlineButton(
                strings.settingsUi.clear,
                {
                    viewModel.setApiKey("")
                    apiKey = ""
                },
                Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(20.dp))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(strings.refreshModels, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = VelumInk)
            Box(
                Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .border(1.dp, VelumDivider, RoundedCornerShape(10.dp))
                    .clickable(enabled = !ui.refreshing) { viewModel.refreshModels() },
                contentAlignment = Alignment.Center,
            ) {
                if (ui.refreshing) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Outlined.Refresh, contentDescription = strings.refreshModels, tint = VelumInk)
                }
            }
        }
        when {
            ui.message?.startsWith("loaded:") == true -> {
                val count = ui.message!!.removePrefix("loaded:")
                Text(
                    strings.settingsUi.modelsLoadedFormat.replace("%d", count),
                    color = VelumInk,
                    modifier = Modifier.padding(20.dp, 8.dp),
                )
            }
            ui.error != null -> {
                val mapped = when (ui.errorStatus) {
                    401, 403 -> strings.settingsUi.modelsErrorUnauthorized
                    404 -> strings.settingsUi.modelsErrorNotFound
                    -1 -> strings.settingsUi.modelsErrorBadUrl
                    else -> ui.error ?: strings.settingsUi.modelsErrorGeneric
                }
                Text(
                    mapped,
                    color = VelumOnWarningContainer,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(20.dp, 8.dp),
                )
            }
        }
        if (settings.cachedModels.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = modelQuery,
                onValueChange = { modelQuery = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                label = { Text(strings.settingsUi.modelSearchHint) },
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
            )
            Spacer(Modifier.height(12.dp))
            TaskModelPicker(
                title = strings.settingsUi.modelForTranscription,
                selectedId = settings.transcriptionModel,
                models = filtered,
                onSelect = viewModel::selectTranscriptionModel,
            )
            if (settings.diarizationMode == ComputeMode.Api) {
                TaskModelPicker(
                    title = strings.settingsUi.modelForDiarization,
                    selectedId = settings.diarizationModel,
                    models = filtered,
                    onSelect = viewModel::selectDiarizationModel,
                )
            }
            TaskModelPicker(
                title = strings.settingsUi.modelForSummaries,
                selectedId = settings.chatModel,
                models = filtered,
                onSelect = viewModel::selectChatModel,
            )
            TaskModelPicker(
                title = strings.settingsUi.modelForEmbeddings,
                selectedId = settings.embeddingModel,
                models = filtered,
                onSelect = viewModel::selectEmbeddingModel,
            )
        }
        if (listingFailed || settings.cachedModels.isEmpty()) {
            Spacer(Modifier.height(12.dp))
            SectionLabel(strings.settingsUi.modelManualId)
            Text(
                strings.settingsUi.modelManualIdHint,
                color = VelumInkSecondary,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            ManualModelField(
                label = strings.settingsUi.modelForTranscription,
                value = manualTranscription,
                onValueChange = { manualTranscription = it },
                onSave = { viewModel.selectTranscriptionModel(manualTranscription.trim()) },
            )
            if (settings.diarizationMode == ComputeMode.Api) {
                ManualModelField(
                    label = strings.settingsUi.modelForDiarization,
                    value = manualDiarization,
                    onValueChange = { manualDiarization = it },
                    onSave = { viewModel.selectDiarizationModel(manualDiarization.trim()) },
                )
            }
            ManualModelField(
                label = strings.settingsUi.modelForSummaries,
                value = manualChat,
                onValueChange = { manualChat = it },
                onSave = { viewModel.selectChatModel(manualChat.trim()) },
            )
            ManualModelField(
                label = strings.settingsUi.modelForEmbeddings,
                value = manualEmbedding,
                onValueChange = { manualEmbedding = it },
                onSave = { viewModel.selectEmbeddingModel(manualEmbedding.trim()) },
            )
        }
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun TaskModelPicker(
    title: String,
    selectedId: String?,
    models: List<AiModelInfo>,
    onSelect: (String) -> Unit,
) {
    Column(Modifier.padding(bottom = 12.dp)) {
        SectionLabel(title)
        models.take(24).forEach { model ->
            val selected = model.id == selectedId
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (selected) VelumSurface else VelumBg)
                    .border(1.dp, if (selected) VelumBlack else VelumDivider, RoundedCornerShape(10.dp))
                    .clickable { onSelect(model.id) }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(model.id, fontWeight = FontWeight.Medium, color = VelumInk, fontSize = 14.sp)
                    model.ownedBy?.let { Text(it, color = VelumInkSecondary, fontSize = 12.sp) }
                }
                Box(
                    Modifier
                        .size(20.dp)
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
                            modifier = Modifier.size(12.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ManualModelField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    onSave: () -> Unit,
) {
    val strings = LocalStrings.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
        label = { Text(label) },
        singleLine = true,
        shape = RoundedCornerShape(10.dp),
    )
    BlackButton(
        strings.save,
        onSave,
        Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        enabled = value.isNotBlank(),
    )
}
