package dev.velum.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.velum.app.di.AppContainer
import dev.velum.app.i18n.LocalStrings
import dev.velum.app.ui.components.ChevronRow
import dev.velum.app.ui.components.ScreenTopBar
import dev.velum.app.ui.components.SectionLabel
import dev.velum.app.ui.components.ToggleRow
import dev.velum.app.ui.theme.VelumSurface
import kotlinx.coroutines.launch

@Composable
fun PreferencesScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onAiProcessing: () -> Unit = {},
    onCustomVocabulary: () -> Unit = {},
) {
    val strings = LocalStrings.current
    val prefs by container.settingsRepository.preferences.collectAsState()
    val settings by container.settingsRepository.settings.collectAsState()
    val scope = rememberCoroutineScope()
    var showAppLockInfo by remember { mutableStateOf(false) }

    if (showAppLockInfo) {
        AlertDialog(
            onDismissRequest = { showAppLockInfo = false },
            title = { Text(strings.appLock) },
            text = { Text(strings.appLockHint + "\n\n" + strings.off) },
            confirmButton = {
                TextButton(onClick = { showAppLockInfo = false }) { Text(strings.close) }
            },
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(VelumSurface)
            .verticalScroll(rememberScrollState()),
    ) {
        ScreenTopBar(strings.preferences, onBack = onBack)
        SectionLabel(strings.aiProcessing)
        ChevronRow(
            strings.aiProcessing,
            onAiProcessing,
            value = if (settings.transcriptionMode.name == "Local") strings.computeLocal else strings.computeApi,
        )
        Spacer(Modifier.height(8.dp))
        SectionLabel(strings.transcriptionSummary)
        ChevronRow(
            strings.transcriptionLanguage,
            onAiProcessing,
            value = settings.audioLanguage,
        )
        ToggleRow(
            strings.cleanTranscript,
            prefs.cleanTranscript,
            { enabled -> scope.launch { container.settingsRepository.setCleanTranscript(enabled) } },
            strings.cleanTranscriptHint,
        )
        ChevronRow(
            strings.autoSpeakerLabel,
            {
                scope.launch {
                    container.settingsRepository.setAutoSpeakerLabel(!prefs.autoSpeakerLabel)
                }
            },
            value = if (prefs.autoSpeakerLabel) strings.on else strings.off,
        )
        ChevronRow(
            strings.customVocabulary,
            onCustomVocabulary,
            value = strings.glossary,
        )
        Spacer(Modifier.height(8.dp))
        SectionLabel(strings.notification)
        ToggleRow(
            strings.messageNotification,
            prefs.autoflowNotify,
            { enabled -> scope.launch { container.settingsRepository.setAutoflowNotify(enabled) } },
            strings.doTranscribeSummarizeHint,
        )
        Spacer(Modifier.height(8.dp))
        SectionLabel(strings.security)
        ChevronRow(
            strings.appLock,
            { showAppLockInfo = true },
            value = strings.off,
            showDivider = false,
        )
        Spacer(Modifier.height(8.dp))
        ToggleRow(
            strings.helpImprove,
            prefs.helpImprove,
            { enabled -> scope.launch { container.settingsRepository.setHelpImprove(enabled) } },
            strings.helpImproveHint,
            showDivider = false,
        )
        Spacer(Modifier.height(40.dp))
    }
}
