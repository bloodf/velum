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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import dev.velum.app.platform.PermissionOutcome
import dev.velum.app.platform.rememberExportFolderPicker
import dev.velum.app.platform.rememberPermissionRequester
import dev.velum.app.ui.components.BlackButton
import dev.velum.app.ui.components.ScreenTopBar
import dev.velum.app.ui.theme.VelumBg
import dev.velum.app.ui.theme.VelumInk
import dev.velum.app.ui.theme.VelumInkSecondary
import dev.velum.app.ui.theme.VelumOnWarningContainer
import dev.velum.app.ui.theme.VelumSurface
import dev.velum.core.calendar.CalendarLookupResult
import dev.velum.core.data.epochSecondsNow
import kotlinx.coroutines.launch

@Composable
fun IntegrationsScreen(
    container: AppContainer,
    onBack: () -> Unit,
) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    var statusLabel by remember { mutableStateOf(strings.localCalendarHint) }
    var statusColor by remember { mutableStateOf(VelumInkSecondary) }
    var needsPermission by remember { mutableStateOf(false) }
    var refreshTick by remember { mutableStateOf(0) }
    var folderUri by remember { mutableStateOf(ExportFolderStore.currentUri()) }
    var webhookUrl by remember { mutableStateOf("") }
    var webhookSecret by remember { mutableStateOf("") }
    var webhookConfigured by remember { mutableStateOf(false) }
    var webhookHasSecret by remember { mutableStateOf(false) }
    var webhookMessage by remember { mutableStateOf<String?>(null) }
    var webhookMessageOk by remember { mutableStateOf(true) }

    val requestCalendar = rememberPermissionRequester(
        permission = AppPermission.ReadCalendar,
        rationaleTitle = strings.calendarPermissionTitle,
        rationaleBody = strings.calendarPermissionRationale,
    )
    val pickExportFolder = rememberExportFolderPicker { uri ->
        folderUri = uri
        refreshTick++
    }

    LaunchedEffect(container, refreshTick) {
        when (val result = container.calendarProvider.currentOrNextEvent(epochSecondsNow() * 1000)) {
            is CalendarLookupResult.Ok -> {
                needsPermission = false
                val title = result.currentOrNext?.title
                if (title.isNullOrBlank()) {
                    statusLabel = strings.oauthDisconnected
                    statusColor = VelumInkSecondary
                } else {
                    statusLabel = "${strings.oauthConnected}: $title"
                    statusColor = Color(0xFF15803D)
                }
            }
            CalendarLookupResult.PermissionDenied -> {
                needsPermission = true
                statusLabel = strings.calendarPermissionBlocked
                statusColor = VelumOnWarningContainer
            }
            is CalendarLookupResult.Unavailable -> {
                needsPermission = false
                statusLabel = if (result.reason.contains("No Android context", ignoreCase = true)) {
                    strings.ux.androidContextUnavailable
                } else {
                    result.reason.ifBlank { strings.ux.androidContextUnavailable }
                }
                statusColor = VelumOnWarningContainer
            }
        }
        folderUri = ExportFolderStore.currentUri()
        webhookConfigured = container.webhookSettings.isConfigured()
        webhookHasSecret = container.webhookSettings.hasSecret()
        if (webhookUrl.isBlank()) {
            webhookUrl = container.webhookSettings.getUrl().orEmpty()
        }
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
                statusColor = statusColor,
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
                statusColor = if (folderUri.isNullOrBlank()) VelumInkSecondary else Color(0xFF15803D),
            ) {
                Spacer(Modifier.height(12.dp))
                BlackButton(strings.ux.folderExportPick, pickExportFolder)
                if (!folderUri.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    TextButton(
                        onClick = {
                            ExportFolderStore.clear()
                            folderUri = null
                            refreshTick++
                        },
                    ) {
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
                    strings.oauthConnected + if (webhookHasSecret) " · HMAC" else ""
                } else {
                    strings.oauthDisconnected
                },
                statusColor = if (webhookConfigured) Color(0xFF15803D) else VelumInkSecondary,
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
                        scope.launch {
                            runCatching {
                                container.webhookSettings.save(webhookUrl, webhookSecret)
                            }.onSuccess {
                                webhookSecret = ""
                                webhookMessage = strings.ux.webhookSaved
                                webhookMessageOk = true
                                refreshTick++
                            }.onFailure {
                                webhookMessage = strings.ux.webhookInvalidUrl
                                webhookMessageOk = false
                            }
                        }
                    },
                    enabled = webhookUrl.isNotBlank(),
                )
                if (webhookConfigured) {
                    Spacer(Modifier.height(4.dp))
                    TextButton(
                        onClick = {
                            scope.launch {
                                container.webhookSettings.clear()
                                webhookUrl = ""
                                webhookSecret = ""
                                webhookMessage = strings.ux.webhookCleared
                                webhookMessageOk = true
                                refreshTick++
                            }
                        },
                    ) {
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
