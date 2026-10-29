package dev.velum.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.velum.app.i18n.LocalStrings
import dev.velum.app.ui.components.ChevronRow
import dev.velum.app.ui.components.ScreenTopBar
import dev.velum.app.ui.components.SectionLabel
import dev.velum.app.ui.theme.VelumSurface

/**
 * Local-only Settings hub. No account / profile / membership chrome —
 * every row navigates to a working screen.
 */
@Composable
fun SettingsHubScreen(
    onBack: () -> Unit,
    onDevices: () -> Unit,
    onRecording: () -> Unit,
    onAiProcessing: () -> Unit,
    onModelManager: () -> Unit,
    onMemory: () -> Unit,
    onPersonalization: () -> Unit,
    onPreferences: () -> Unit,
    onApi: () -> Unit,
    onVoiceProfiles: () -> Unit,
    onTemplates: () -> Unit,
    onAutoFlow: () -> Unit,
    onIntegrations: () -> Unit,
    onWatchedFolders: () -> Unit,
    onAppearance: () -> Unit,
    onStoragePrivacy: () -> Unit,
    onAbout: () -> Unit,
) {
    val strings = LocalStrings.current
    // Pin ScreenTopBar outside the scroll so Back stays reachable after
    // scrolling to Appearance / Storage rows (device-walk #106).
    Column(
        Modifier
            .fillMaxSize()
            .background(VelumSurface),
    ) {
        ScreenTopBar(strings.settings, onBack = onBack)
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            SectionLabel(strings.settingsUi.sectionDevices)
            ChevronRow(
                strings.settingsUi.sectionDevices,
                onDevices,
                icon = Icons.Outlined.Devices,
                showDivider = false,
            )

            SectionLabel(strings.settingsUi.sectionRecording)
            ChevronRow(strings.settingsUi.recording, onRecording, icon = Icons.Outlined.Mic, showDivider = false)

            SectionLabel(strings.settingsUi.sectionTranscriptionAi)
            ChevronRow(strings.aiProcessing, onAiProcessing, icon = Icons.Outlined.Tune)
            ChevronRow(strings.modelManager, onModelManager, icon = Icons.Outlined.Cloud)
            ChevronRow(strings.customVocabulary, onMemory, icon = Icons.Outlined.Memory)
            ChevronRow(strings.personalization, onPersonalization, icon = Icons.Outlined.AutoAwesome)
            ChevronRow(strings.preferences, onPreferences, icon = Icons.Outlined.Tune, showDivider = false)

            SectionLabel(strings.settingsUi.sectionApi)
            ChevronRow(strings.aiProvider, onApi, icon = Icons.Outlined.Key, showDivider = false)

            SectionLabel(strings.settingsUi.sectionSpeakers)
            ChevronRow(strings.voiceProfiles, onVoiceProfiles, icon = Icons.Outlined.RecordVoiceOver, showDivider = false)

            SectionLabel(strings.settingsUi.sectionTemplates)
            ChevronRow(
                strings.templatesCommunity,
                onTemplates,
                icon = Icons.AutoMirrored.Outlined.LibraryBooks,
                showDivider = false,
            )

            SectionLabel(strings.settingsUi.sectionAutomation)
            ChevronRow(strings.autoFlow, onAutoFlow, icon = Icons.Outlined.AccountTree)
            ChevronRow(strings.integrations, onIntegrations, icon = Icons.Outlined.Extension)
            ChevronRow(
                strings.settingsUi.watchedFolders,
                onWatchedFolders,
                icon = Icons.Outlined.FolderOpen,
                showDivider = false,
            )

            SectionLabel(strings.settingsUi.sectionAppearance)
            ChevronRow(strings.settingsUi.appearance, onAppearance, icon = Icons.Outlined.Palette, showDivider = false)

            SectionLabel(strings.settingsUi.sectionStorage)
            ChevronRow(
                strings.settingsUi.storagePrivacy,
                onStoragePrivacy,
                icon = Icons.Outlined.Security,
                showDivider = false,
            )

            SectionLabel(strings.settingsUi.sectionAbout)
            ChevronRow(strings.aboutVelum, onAbout, icon = Icons.Outlined.Info, showDivider = false)

            Spacer(Modifier.height(40.dp))
        }
    }
}
