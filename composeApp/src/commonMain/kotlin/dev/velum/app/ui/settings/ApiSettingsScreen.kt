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
import dev.velum.core.settings.AiApiTask
import dev.velum.core.settings.ApiEndpointProfile
import dev.velum.core.settings.ApiModelResolver
import dev.velum.core.settings.SettingsRepository
import dev.velum.core.settings.TaskModelBinding

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

    val profiles = settings.profiles.ifEmpty {
        listOf(
            ApiEndpointProfile(
                id = ApiModelResolver.DEFAULT_PROFILE_ID,
                name = ApiModelResolver.DEFAULT_PROFILE_NAME,
                baseUrl = settings.baseUrl,
            ),
        )
    }
    val activeId = settings.activeProfileId ?: profiles.first().id
    var selectedProfileId by remember(activeId) { mutableStateOf(activeId) }
    val selectedProfile = profiles.firstOrNull { it.id == selectedProfileId } ?: profiles.first()

    var baseUrl by remember(selectedProfile.id, selectedProfile.baseUrl) {
        mutableStateOf(selectedProfile.baseUrl)
    }
    var profileName by remember(selectedProfile.id, selectedProfile.name) {
        mutableStateOf(selectedProfile.name)
    }
    var apiKey by remember(selectedProfile.id) { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var modelQuery by remember { mutableStateOf("") }

    val profileModels = settings.cachedModelsByProfile[selectedProfile.id]
        ?.takeIf { it.isNotEmpty() }
        ?: settings.cachedModels
    val listingFailed = ui.error != null && !ui.refreshing
    val filtered = remember(profileModels, modelQuery) {
        val q = modelQuery.trim().lowercase()
        if (q.isEmpty()) profileModels
        else profileModels.filter {
            it.id.lowercase().contains(q) || (it.ownedBy?.lowercase()?.contains(q) == true)
        }
    }

    fun taskLabel(task: AiApiTask): String = when (task) {
        AiApiTask.Transcription -> strings.settingsUi.modelForTranscription
        AiApiTask.Diarization -> strings.settingsUi.modelForDiarization
        AiApiTask.Summary -> strings.settingsUi.modelForSummaries
        AiApiTask.Ask -> strings.settingsUi.modelForAsk
        AiApiTask.AutoTitle -> strings.settingsUi.modelForAutoTitle
        AiApiTask.Translation -> strings.settingsUi.modelForTranslation
        AiApiTask.AutoFlow -> strings.settingsUi.modelForAutoFlow
    }

    // Pin ScreenTopBar outside the scroll so Back stays reachable (#118).
    Column(
        Modifier
            .fillMaxSize()
            .background(VelumBg),
    ) {
        ScreenTopBar(strings.aiProvider, onBack = onBack)
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
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
            Spacer(Modifier.height(16.dp))
            SectionLabel(strings.settingsUi.endpointProfiles)
            profiles.forEach { profile ->
                val selected = profile.id == selectedProfileId
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (selected) VelumSurface else VelumBg)
                        .border(1.dp, if (selected) VelumBlack else VelumDivider, RoundedCornerShape(10.dp))
                        .clickable {
                            selectedProfileId = profile.id
                            viewModel.setActiveProfile(profile.id)
                        }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(profile.name, fontWeight = FontWeight.Medium, color = VelumInk, fontSize = 14.sp)
                        Text(profile.baseUrl, color = VelumInkSecondary, fontSize = 12.sp)
                    }
                    if (selected) {
                        Icon(Icons.Outlined.Check, contentDescription = null, tint = VelumInk, modifier = Modifier.size(18.dp))
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            BlackButton(
                strings.settingsUi.addEndpointProfile,
                {
                    val id = "profile-${profiles.size + 1}"
                    viewModel.upsertProfile(
                        ApiEndpointProfile(
                            id = id,
                            name = id,
                            baseUrl = SettingsRepository.DEFAULT_BASE_URL,
                        ),
                    )
                    selectedProfileId = id
                },
                Modifier.padding(horizontal = 20.dp),
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = profileName,
                onValueChange = { profileName = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                label = { Text(strings.settingsUi.profileName) },
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                label = { Text(strings.baseUrl) },
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
            )
            Spacer(Modifier.height(8.dp))
            BlackButton(
                strings.save,
                {
                    viewModel.upsertProfile(
                        selectedProfile.copy(name = profileName.trim().ifBlank { selectedProfile.id }, baseUrl = baseUrl),
                    )
                },
                Modifier.padding(horizontal = 20.dp),
            )
            if (selectedProfile.id != ApiModelResolver.DEFAULT_PROFILE_ID) {
                Spacer(Modifier.height(8.dp))
                OutlineButton(
                    strings.settingsUi.deleteProfile,
                    {
                        viewModel.deleteProfile(selectedProfile.id)
                        selectedProfileId = ApiModelResolver.DEFAULT_PROFILE_ID
                    },
                    Modifier.padding(horizontal = 20.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                label = {
                    Text(
                        if (settings.hasApiKey && selectedProfile.id == activeId) {
                            "${strings.apiKey} (${strings.enabled})"
                        } else {
                            strings.apiKey
                        },
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
                        viewModel.setProfileApiKey(selectedProfile.id, apiKey)
                        apiKey = ""
                        viewModel.refreshModels(selectedProfile.id)
                    },
                    Modifier.weight(1f),
                    enabled = apiKey.isNotBlank(),
                )
                OutlineButton(
                    strings.settingsUi.clear,
                    {
                        viewModel.setProfileApiKey(selectedProfile.id, "")
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
                        .clickable(enabled = !ui.refreshing) { viewModel.refreshModels(selectedProfile.id) },
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
                        else -> strings.settingsUi.modelsErrorGeneric
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
            Spacer(Modifier.height(12.dp))
            SectionLabel(strings.settingsUi.modelsByTask)
            Text(
                strings.settingsUi.diarizationApiLocalOnly,
                color = VelumInkSecondary,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            if (profileModels.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = modelQuery,
                    onValueChange = { modelQuery = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    label = { Text(strings.settingsUi.modelSearchHint) },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                )
            }
            AiApiTask.entries.forEach { task ->
                TaskBindingEditor(
                    task = task,
                    title = taskLabel(task),
                    binding = settings.taskBindings[task.name] ?: TaskModelBinding(),
                    profiles = profiles,
                    fallbackProfileId = selectedProfile.id,
                    filteredModels = filtered,
                    showManual = listingFailed || profileModels.isEmpty(),
                    onBind = viewModel::setTaskBinding,
                )
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun TaskBindingEditor(
    task: AiApiTask,
    title: String,
    binding: TaskModelBinding,
    profiles: List<ApiEndpointProfile>,
    fallbackProfileId: String,
    filteredModels: List<AiModelInfo>,
    showManual: Boolean,
    onBind: (AiApiTask, TaskModelBinding) -> Unit,
) {
    val strings = LocalStrings.current
    val selectedModel = binding.modelId
    val boundProfileId = binding.profileId ?: fallbackProfileId
    var manual by remember(task.name, selectedModel) { mutableStateOf(selectedModel.orEmpty()) }
    Spacer(Modifier.height(12.dp))
    Text(
        title,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        color = VelumInk,
        modifier = Modifier.padding(horizontal = 20.dp),
    )
    ChevronRow(
        strings.settingsUi.endpointProfile,
        {
            val idx = profiles.indexOfFirst { it.id == boundProfileId }.coerceAtLeast(0)
            val next = profiles[(idx + 1) % profiles.size]
            onBind(task, binding.copy(profileId = next.id))
        },
        value = profiles.firstOrNull { it.id == boundProfileId }?.name
            ?: profiles.firstOrNull()?.name.orEmpty(),
    )
    if (filteredModels.isNotEmpty()) {
        TaskModelPicker(
            title = strings.settingsUi.modelManualId,
            selectedId = selectedModel,
            models = filteredModels,
            onSelect = { modelId ->
                onBind(task, binding.copy(profileId = boundProfileId, modelId = modelId))
            },
        )
    } else if (showManual) {
        ManualModelField(
            label = title,
            value = manual,
            onValueChange = { manual = it },
            onSave = {
                onBind(
                    task,
                    binding.copy(
                        profileId = boundProfileId,
                        modelId = manual.trim().ifBlank { null },
                    ),
                )
            },
        )
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
