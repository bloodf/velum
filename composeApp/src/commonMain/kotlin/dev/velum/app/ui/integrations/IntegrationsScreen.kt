package dev.velum.app.ui.integrations

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.velum.app.di.AppContainer
import dev.velum.app.i18n.LocalStrings
import dev.velum.app.platform.AppPermission
import dev.velum.app.platform.ExportFolderStore
import dev.velum.app.platform.HapticKind
import dev.velum.app.platform.LocalHaptics
import dev.velum.app.platform.PermissionOutcome
import dev.velum.app.platform.rememberExportFolderPicker
import dev.velum.app.platform.rememberPermissionRequester
import dev.velum.app.ui.components.BlackButton
import dev.velum.app.ui.components.OutlineButton
import dev.velum.app.ui.components.ScreenTopBar
import dev.velum.app.ui.theme.VelumBg
import dev.velum.app.ui.theme.VelumInk
import dev.velum.app.ui.theme.VelumInkSecondary
import dev.velum.app.ui.theme.VelumOnWarningContainer
import dev.velum.app.ui.theme.VelumSurface
import dev.velum.app.ui.theme.velumColors
import dev.velum.core.calendar.CalendarLookupResult
import dev.velum.core.data.epochSecondsNow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class StatusTone { Neutral, Ok, Warn }

@Composable
fun IntegrationsScreen(
    container: AppContainer,
    onBack: () -> Unit,
) {
    val strings = LocalStrings.current
    val haptics = LocalHaptics.current
    val storageState by container.settingsRepository.loadState.collectAsState()
    if (storageState != dev.velum.core.settings.SettingsLoadState.Ready) {
        Column(Modifier.fillMaxSize().background(VelumBg)) {
            ScreenTopBar(strings.integrations, onBack = onBack)
            if (storageState == dev.velum.core.settings.SettingsLoadState.Unavailable) {
                Text(strings.ux.secureStorageUnavailable, modifier = Modifier.padding(20.dp), color = VelumInk)
                OutlineButton(strings.ux.retryProcessing, { container.settingsViewModel.reloadSettings() }, Modifier.padding(20.dp))
            } else {
                CircularProgressIndicator(Modifier.padding(20.dp))
            }
        }
        return
    }
    var statusLabel by remember { mutableStateOf(strings.localCalendarHint) }
    // Store the tone, not the colour: colour tokens must be read at use so a theme flip applies.
    // LocalVelumColors is provided by VelumTheme during composition, so this follows a theme flip on its own.
    val palette = velumColors()
    var statusTone by remember { mutableStateOf(StatusTone.Neutral) }
    var needsPermission by remember { mutableStateOf(false) }
    var refreshTick by remember { mutableStateOf(0) }
    // SharedPreferences read: loaded off the main thread (StrictMode), null until then.
    var folderUri by remember { mutableStateOf<String?>(null) }
    val ioScope = rememberCoroutineScope()
    var webhookUrl by remember { mutableStateOf("") }
    var webhookSecret by remember { mutableStateOf("") }
    var webhookConfigured by remember { mutableStateOf(false) }
    var webhookHasSecret by remember { mutableStateOf(false) }
    var webhookMessage by remember { mutableStateOf<String?>(null) }
    var webhookMessageOk by remember { mutableStateOf(true) }
    var confirmClearFolder by remember { mutableStateOf(false) }
    var confirmClearWebhook by remember { mutableStateOf(false) }
    var testingWebhook by remember { mutableStateOf(false) }

    val requestCalendar = rememberPermissionRequester(
        permission = AppPermission.ReadCalendar,
        rationaleTitle = strings.calendarPermissionTitle,
        rationaleBody = strings.calendarPermissionRationale,
    )
    val pickExportFolder = rememberExportFolderPicker { uri ->
        folderUri = uri
        refreshTick++
    }

    LaunchedEffect(refreshTick) {
        folderUri = withContext(Dispatchers.IO) { ExportFolderStore.currentUri() }
    }

    LaunchedEffect(container, refreshTick) {
        when (val result = container.calendarProvider.currentOrNextEvent(epochSecondsNow() * 1000)) {
            is CalendarLookupResult.Ok -> {
                needsPermission = false
                val title = result.currentOrNext?.title
                if (title.isNullOrBlank()) {
                    statusLabel = strings.oauthDisconnected
                    statusTone = StatusTone.Neutral
                } else {
                    statusLabel = "${strings.oauthConnected}: $title"
                    statusTone = StatusTone.Ok
                }
            }
            CalendarLookupResult.PermissionDenied -> {
                needsPermission = true
                statusLabel = strings.calendarPermissionBlocked
                statusTone = StatusTone.Warn
            }
            is CalendarLookupResult.Unavailable -> {
                needsPermission = false
                statusLabel = if (result.reason.contains("No Android context", ignoreCase = true)) {
                    strings.ux.androidContextUnavailable
                } else {
                    result.reason.ifBlank { strings.ux.androidContextUnavailable }
                }
                statusTone = StatusTone.Warn
            }
        }
        container.settingsViewModel.handleStorageFailure {
            val configured = container.webhookSettings.isConfigured()
            val hasSecret = container.webhookSettings.hasSecret()
            val url = if (webhookUrl.isBlank()) container.webhookSettings.getUrl().orEmpty() else webhookUrl
            webhookConfigured = configured
            webhookHasSecret = hasSecret
            webhookUrl = url
        }
    }

    if (confirmClearFolder) {
        AlertDialog(
            onDismissRequest = { confirmClearFolder = false },
            title = { Text(strings.ux.confirmClearTitle) },
            text = { Text(strings.ux.confirmClearFolderBody) },
            confirmButton = {
                TextButton(
                    onClick = {
                        haptics.perform(HapticKind.Medium)
                        confirmClearFolder = false
                        folderUri = null
                        ioScope.launch {
                            withContext(Dispatchers.IO) { ExportFolderStore.clear() }
                            refreshTick++
                        }
                    },
                ) { Text(strings.confirm) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearFolder = false }) { Text(strings.cancel) }
            },
        )
    }

    if (confirmClearWebhook) {
        AlertDialog(
            onDismissRequest = { confirmClearWebhook = false },
            title = { Text(strings.ux.confirmClearTitle) },
            text = { Text(strings.ux.confirmClearWebhookBody) },
            confirmButton = {
                TextButton(
                    onClick = {
                        haptics.perform(HapticKind.Medium)
                        confirmClearWebhook = false
                        container.settingsViewModel.runStorageAction {
                            container.webhookSettings.clear()
                            webhookUrl = ""
                            webhookSecret = ""
                            webhookMessage = strings.ux.webhookCleared
                            webhookMessageOk = true
                            refreshTick++
                        }
                    },
                ) { Text(strings.confirm) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearWebhook = false }) { Text(strings.cancel) }
            },
        )
    }

    Column(Modifier.fillMaxSize().background(VelumBg)) {
        ScreenTopBar(strings.integrations, onBack = onBack)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            IntegrationCard(
                icon = Icons.Outlined.CalendarMonth,
                iconTint = Color(0xFF4285F4),
                title = strings.localCalendar,
                body = strings.localCalendarHint,
                status = statusLabel,
                statusColor = when (statusTone) {
                    StatusTone.Neutral -> palette.inkSecondary
                    StatusTone.Ok -> Color(0xFF15803D)
                    StatusTone.Warn -> palette.onWarningContainer
                },
            ) {
                if (needsPermission) {
                    Spacer(Modifier.height(12.dp))
                    BlackButton(
                        strings.grantCalendarPermission,
                        {
                            requestCalendar { outcome ->
                                if (outcome == PermissionOutcome.Granted ||
                                    outcome == PermissionOutcome.NotRequired
                                ) {
                                    refreshTick++
                                }
                            }
                        },
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            IntegrationCard(
                icon = Icons.Outlined.FolderOpen,
                iconTint = Color(0xFF0F766E),
                title = strings.ux.folderExportTitle,
                body = strings.ux.folderExportHint,
                status = if (folderUri.isNullOrBlank()) {
                    strings.oauthDisconnected
                } else {
                    strings.ux.folderExportConfigured
                },
                statusColor = if (folderUri.isNullOrBlank()) palette.inkSecondary else Color(0xFF15803D),
            ) {
                Spacer(Modifier.height(12.dp))
                BlackButton(strings.ux.folderExportPick, pickExportFolder)
                if (!folderUri.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = { confirmClearFolder = true }) {
                        Text(strings.ux.folderExportClear, color = VelumInkSecondary)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            IntegrationCard(
                icon = Icons.Outlined.Share,
                iconTint = Color(0xFFB45309),
                title = strings.ux.webhookTitle,
                body = strings.ux.webhookHint,
                status = if (webhookConfigured) {
                    strings.oauthConnected + if (webhookHasSecret) " · ${strings.ux.webhookSigned}" else ""
                } else {
                    strings.oauthDisconnected
                },
                statusColor = if (webhookConfigured) Color(0xFF15803D) else palette.inkSecondary,
            ) {
                Spacer(Modifier.height(12.dp))
                Text(strings.ux.webhookUrl, color = VelumInkSecondary, fontSize = 13.sp)
                Spacer(Modifier.height(4.dp))
                BasicTextField(
                    value = webhookUrl,
                    onValueChange = { webhookUrl = it },
                    singleLine = true,
                    cursorBrush = SolidColor(VelumInk),
                    textStyle = TextStyle(color = VelumInk, fontSize = 15.sp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(VelumBg)
                        .padding(12.dp),
                )
                Spacer(Modifier.height(10.dp))
                Text(strings.ux.webhookSecret, color = VelumInkSecondary, fontSize = 13.sp)
                Spacer(Modifier.height(4.dp))
                BasicTextField(
                    value = webhookSecret,
                    onValueChange = { webhookSecret = it },
                    singleLine = true,
                    cursorBrush = SolidColor(VelumInk),
                    textStyle = TextStyle(color = VelumInk, fontSize = 15.sp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(VelumBg)
                        .padding(12.dp),
                )
                Text(
                    strings.ux.webhookSecretHint,
                    color = VelumInkSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Spacer(Modifier.height(12.dp))
                BlackButton(
                    strings.ux.webhookSave,
                    {
                        container.settingsViewModel.runStorageAction {
                            runCatching {
                                container.webhookSettings.save(webhookUrl, webhookSecret)
                            }.onSuccess {
                                webhookSecret = ""
                                webhookMessage = strings.ux.webhookSaved
                                webhookMessageOk = true
                                refreshTick++
                            }.onFailure {
                                if (it is kotlinx.coroutines.CancellationException || it is dev.velum.core.security.SecureStoreUnavailableException) throw it
                                webhookMessage = strings.ux.webhookInvalidUrl
                                webhookMessageOk = false
                            }
                        }
                    },
                    enabled = webhookUrl.isNotBlank(),
                )
                if (webhookConfigured) {
                    Spacer(Modifier.height(8.dp))
                    OutlineButton(
                        strings.ux.webhookTest,
                        {
                            if (testingWebhook) return@OutlineButton
                            container.settingsViewModel.runStorageAction {
                                testingWebhook = true
                                try {
                                    val detail = try {
                                        container.webhookDispatcher.ping()
                                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                        throw cancelled
                                    } catch (storage: dev.velum.core.security.SecureStoreUnavailableException) {
                                        throw storage
                                    } catch (_: Exception) {
                                        null
                                    }
                                    when {
                                        detail == null -> {
                                            webhookMessage = strings.ux.webhookNotConfigured
                                            webhookMessageOk = false
                                        }
                                        WebhookTestResult.isHttpSuccess(detail) -> {
                                            webhookMessage = strings.ux.webhookPostedFormat.format(detail)
                                            webhookMessageOk = true
                                        }
                                        else -> {
                                            webhookMessage = strings.ux.webhookFailedFormat.format(detail)
                                            webhookMessageOk = false
                                        }
                                    }
                                } finally {
                                    testingWebhook = false
                                }
                            }
                        },
                        enabled = !testingWebhook,
                    )
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = { confirmClearWebhook = true }) {
                        Text(strings.ux.webhookClear, color = VelumInkSecondary)
                    }
                }
                webhookMessage?.let { msg ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        msg,
                        color = if (webhookMessageOk) Color(0xFF15803D) else VelumOnWarningContainer,
                        fontSize = 13.sp,
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun IntegrationCard(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    body: String,
    status: String,
    statusColor: Color,
    content: @Composable () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(VelumSurface)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(iconTint.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Text(
                title,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                color = VelumInk,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(body, color = VelumInkSecondary, fontSize = 13.sp, lineHeight = 18.sp)
        Spacer(Modifier.height(8.dp))
        Text(status, color = statusColor, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        content()
    }
}
