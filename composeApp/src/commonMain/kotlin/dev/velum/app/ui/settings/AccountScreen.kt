package dev.velum.app.ui.settings

import androidx.compose.foundation.background
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
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
import dev.velum.app.ui.theme.VelumBg
import dev.velum.app.ui.theme.VelumInk
import dev.velum.app.ui.theme.VelumInkSecondary
import dev.velum.app.ui.theme.VelumSurface

@Composable
fun AccountScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onDevice: () -> Unit,
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

    Column(
        Modifier
            .fillMaxSize()
            .background(VelumBg)
            .verticalScroll(rememberScrollState()),
    ) {
        ScreenTopBar(strings.account, onBack = onBack)
        Spacer(Modifier.height(8.dp))
        ChevronRow(strings.aiProcessing, onAiProcessing)
        ChevronRow(strings.modelManager, onModelManager, showDivider = false)
        Spacer(Modifier.height(16.dp))
        Text(
            strings.aiProvider,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
            color = VelumInk,
            modifier = Modifier.padding(horizontal = 20.dp),
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
        BlackButton(strings.save, { viewModel.setBaseUrl(baseUrl) }, Modifier.padding(horizontal = 20.dp))
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            label = {
                Text(
                    if (settings.hasApiKey) "${strings.apiKey} (saved)" else strings.apiKey,
                )
            },
            singleLine = true,
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                Text(
                    if (showKey) "Hide" else "Show",
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
                },
                Modifier.weight(1f),
                enabled = apiKey.isNotBlank(),
            )
            OutlineButton(
                "Clear",
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
            Text(strings.refreshModels, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            OutlineButton(
                if (ui.refreshing) "…" else strings.refreshModels,
                { viewModel.refreshModels() },
                Modifier.clip(RoundedCornerShape(10.dp)).fillMaxWidth(0.45f),
            )
        }
        ui.message?.let {
            Text(it, color = VelumInk, modifier = Modifier.padding(20.dp, 8.dp))
        }
        ui.error?.let {
            Text(it, color = androidx.compose.ui.graphics.Color(0xFFFF3B30), modifier = Modifier.padding(20.dp, 8.dp))
        }
        if (ui.refreshing) {
            CircularProgressIndicator(Modifier.padding(20.dp).height(24.dp))
        }
        settings.cachedModels.take(12).forEach { model ->
            val selected = model.id == settings.chatModel || model.id == settings.transcriptionModel
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (selected) VelumSurface else VelumBg)
                    .clickable {
                        viewModel.selectTranscriptionModel(model.id)
                        viewModel.selectChatModel(model.id)
                    }
                    .padding(12.dp),
            ) {
                Text(model.id, fontWeight = FontWeight.Medium, color = VelumInk)
                model.ownedBy?.let { Text(it, color = VelumInkSecondary, fontSize = 13.sp) }
            }
        }
        Spacer(Modifier.height(16.dp))
        ChevronRow(strings.deviceManagement, onDevice)
        ChevronRow(strings.importDeviceKeys, {}, showDivider = false)
        Spacer(Modifier.height(40.dp))
    }
}
