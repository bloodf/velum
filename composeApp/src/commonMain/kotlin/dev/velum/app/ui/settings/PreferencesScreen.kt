package dev.velum.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.velum.app.i18n.LocalStrings
import dev.velum.app.ui.components.ChevronRow
import dev.velum.app.ui.components.ScreenTopBar
import dev.velum.app.ui.components.SectionLabel
import dev.velum.app.ui.components.ToggleRow
import dev.velum.app.ui.theme.VelumSurface

@Composable
fun PreferencesScreen(
    onBack: () -> Unit,
    onAiProcessing: () -> Unit = {},
) {
    val strings = LocalStrings.current
    var clean by remember { mutableStateOf(true) }
    var analytics by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .background(VelumSurface)
            .verticalScroll(rememberScrollState()),
    ) {
        ScreenTopBar(strings.preferences, onBack = onBack)
        SectionLabel(strings.aiProcessing)
        ChevronRow(strings.aiProcessing, onAiProcessing, value = strings.computeLocal)
        Spacer(Modifier.height(8.dp))
        SectionLabel(strings.transcriptionSummary)
        ChevronRow(
            strings.transcriptionLanguage,
            {},
            value = strings.off,
        )
        ToggleRow(strings.cleanTranscript, clean, { clean = it }, strings.cleanTranscriptHint)
        ChevronRow(strings.autoSpeakerLabel, {}, value = strings.off)
        ChevronRow(strings.customVocabulary, {}, value = strings.off)
        Spacer(Modifier.height(8.dp))
        SectionLabel(strings.notification)
        ChevronRow(strings.messageNotification, {})
        Spacer(Modifier.height(8.dp))
        SectionLabel(strings.security)
        ChevronRow(strings.appLock, {}, value = strings.off)
        Spacer(Modifier.height(8.dp))
        ToggleRow(strings.helpImprove, analytics, { analytics = it }, strings.helpImproveHint, showDivider = false)
        Spacer(Modifier.height(40.dp))
    }
}
