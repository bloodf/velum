package dev.velum.app.ui.integrations

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.velum.ai.integrations.DestinationResult
import dev.velum.app.di.AppContainer
import dev.velum.app.i18n.LocalStrings
import dev.velum.app.i18n.NoteDestination
import dev.velum.app.i18n.destinationResultText
import dev.velum.app.ui.components.BlackButton
import dev.velum.app.ui.components.OutlineButton
import dev.velum.app.ui.theme.VelumBg
import dev.velum.app.ui.theme.VelumInk
import dev.velum.app.ui.theme.VelumInkSecondary
import dev.velum.app.ui.theme.VelumOnWarningContainer
import dev.velum.app.ui.theme.velumColors
import dev.velum.core.integrations.NotionIds
import dev.velum.core.integrations.SlackWebhookUrl
import dev.velum.core.security.SecureStoreUnavailableException
import kotlinx.coroutines.CancellationException

private val OkGreen = Color(0xFF15803D)

/**
 * Slack Incoming Webhook (#61). The saved URL is a credential (anyone holding it can post to the channel), so it is
 * never read back into the field: the card only says that one is saved.
 */
@Composable
internal fun SlackCard(container: AppContainer) {
    val strings = LocalStrings.current
    val d = strings.added.destinations
    val palette = velumColors()
    var configured by remember { mutableStateOf(false) }
    var url by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var messageOk by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var refresh by remember { mutableStateOf(0) }

    LaunchedEffect(container, refresh) {
        container.settingsViewModel.handleStorageFailure { configured = container.slackSettings.isConfigured() }
    }

    if (confirmClear) {
        ClearDialog(d.slackClearBody, onDismiss = { confirmClear = false }) {
            confirmClear = false
            container.settingsViewModel.runStorageAction {
                container.slackSettings.clear()
                url = ""
                message = d.slackCleared
                messageOk = true
                refresh++
            }
        }
    }

    IntegrationCard(
        icon = Icons.Outlined.Tag,
        iconTint = Color(0xFF4A154B),
        title = d.slackTitle,
        body = d.slackHint,
        status = if (configured) strings.oauthConnected else strings.oauthDisconnected,
        statusColor = if (configured) OkGreen else palette.inkSecondary,
    ) {
        Spacer(Modifier.height(12.dp))
        FieldLabel(d.slackUrl)
        CardTextField(url, { url = it })
        if (configured) Hint(d.slackUrlSavedHint)
        Spacer(Modifier.height(12.dp))
        BlackButton(
            strings.save,
            {
                container.settingsViewModel.runStorageAction {
                    val valid = runCatching { SlackWebhookUrl.normalizeAndValidate(url) }.isSuccess
                    if (!valid) {
                        message = d.slackInvalidUrl
                        messageOk = false
                        return@runStorageAction
                    }
                    container.slackSettings.save(url)
                    url = ""
                    message = d.slackSaved
                    messageOk = true
                    refresh++
                }
            },
            enabled = url.isNotBlank(),
        )
        if (configured) {
            Spacer(Modifier.height(8.dp))
            OutlineButton(
                d.slackTest,
                {
                    if (busy) return@OutlineButton
                    container.settingsViewModel.runStorageAction {
                        busy = true
                        try {
                            val result = guarded { container.slackPoster.test(d.slackTestMessage) }
                            message = strings.destinationResultText(NoteDestination.Slack, result, test = true)
                            messageOk = result is DestinationResult.Sent
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = !busy,
            )
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = { confirmClear = true }) { Text(d.slackClear, color = VelumInkSecondary) }
        }
        ResultLine(message, messageOk)
    }
}

/**
 * Notion internal integration (#59): a token the user creates in their own workspace plus the database to write to.
 * The token is masked while typed and never read back.
 */
@Composable
internal fun NotionCard(container: AppContainer) {
    val strings = LocalStrings.current
    val d = strings.added.destinations
    val palette = velumColors()
    var configured by remember { mutableStateOf(false) }
    var token by remember { mutableStateOf("") }
    var database by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var messageOk by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var refresh by remember { mutableStateOf(0) }

    LaunchedEffect(container, refresh) {
        container.settingsViewModel.handleStorageFailure { configured = container.notionSettings.isConfigured() }
    }

    if (confirmClear) {
        ClearDialog(d.notionClearBody, onDismiss = { confirmClear = false }) {
            confirmClear = false
            container.settingsViewModel.runStorageAction {
                container.notionSettings.clear()
                token = ""
                database = ""
                message = d.notionCleared
                messageOk = true
                refresh++
            }
        }
    }

    IntegrationCard(
        icon = Icons.Outlined.Description,
        iconTint = Color(0xFF37352F),
        title = d.notionTitle,
        body = d.notionHint,
        status = if (configured) strings.oauthConnected else strings.oauthDisconnected,
        statusColor = if (configured) OkGreen else palette.inkSecondary,
    ) {
        Spacer(Modifier.height(12.dp))
        FieldLabel(d.notionToken)
        CardTextField(token, { token = it }, masked = true)
        Spacer(Modifier.height(10.dp))
        FieldLabel(d.notionDatabase)
        CardTextField(database, { database = it })
        Hint(if (configured) d.notionTokenSavedHint else d.notionDatabaseHint)
        Spacer(Modifier.height(12.dp))
        BlackButton(
            strings.save,
            {
                container.settingsViewModel.runStorageAction {
                    if (runCatching { NotionIds.validateToken(token) }.isFailure) {
                        message = d.notionInvalidToken
                        messageOk = false
                        return@runStorageAction
                    }
                    if (runCatching { NotionIds.databaseId(database) }.isFailure) {
                        message = d.notionInvalidDatabase
                        messageOk = false
                        return@runStorageAction
                    }
                    container.notionSettings.save(token, database)
                    token = ""
                    database = ""
                    refresh++
                    // Check right away: the usual mistake is a database not shared with the integration.
                    busy = true
                    try {
                        val result = guarded { container.notionExporter.test() }
                        message = if (result is DestinationResult.Sent) {
                            d.notionSaved + " " + strings.destinationResultText(NoteDestination.Notion, result, test = true)
                        } else {
                            strings.destinationResultText(NoteDestination.Notion, result, test = true)
                        }
                        messageOk = result is DestinationResult.Sent
                    } finally {
                        busy = false
                    }
                }
            },
            enabled = token.isNotBlank() && database.isNotBlank() && !busy,
        )
        if (configured) {
            Spacer(Modifier.height(8.dp))
            OutlineButton(
                d.notionTest,
                {
                    if (busy) return@OutlineButton
                    container.settingsViewModel.runStorageAction {
                        busy = true
                        try {
                            val result = guarded { container.notionExporter.test() }
                            message = strings.destinationResultText(NoteDestination.Notion, result, test = true)
                            messageOk = result is DestinationResult.Sent
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = !busy,
            )
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = { confirmClear = true }) { Text(d.notionClear, color = VelumInkSecondary) }
        }
        ResultLine(message, messageOk)
    }
}

/** Network failures become a result; cancellation and a locked secure store propagate to their handlers. */
internal suspend fun guarded(block: suspend () -> DestinationResult): DestinationResult = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (storage: SecureStoreUnavailableException) {
    throw storage
} catch (_: Exception) {
    DestinationResult.Failed(dev.velum.ai.integrations.WebhookFailure.CONNECTION)
}

@Composable
private fun ClearDialog(body: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val strings = LocalStrings.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.ux.confirmClearTitle) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(strings.confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.cancel) } },
    )
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, color = VelumInkSecondary, fontSize = 13.sp)
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun Hint(text: String) {
    Text(text, color = VelumInkSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun CardTextField(value: String, onValueChange: (String) -> Unit, masked: Boolean = false) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        cursorBrush = SolidColor(VelumInk),
        textStyle = TextStyle(color = VelumInk, fontSize = 15.sp),
        visualTransformation = if (masked) PasswordVisualTransformation() else VisualTransformation.None,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(VelumBg)
            .padding(12.dp),
    )
}

@Composable
private fun ResultLine(message: String?, ok: Boolean) {
    message ?: return
    Spacer(Modifier.height(8.dp))
    Text(message, color = if (ok) OkGreen else VelumOnWarningContainer, fontSize = 13.sp)
}
