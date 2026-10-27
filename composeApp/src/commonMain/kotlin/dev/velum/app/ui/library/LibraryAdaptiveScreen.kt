package dev.velum.app.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.MicExternalOn
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.velum.app.di.AppContainer
import dev.velum.app.i18n.LocalStrings
import dev.velum.app.ui.adaptive.WindowWidthSizeClass
import dev.velum.app.ui.adaptive.WithWindowWidthSizeClass
import dev.velum.app.ui.ask.AskAcrossScreen
import dev.velum.app.ui.files.FilesScreen
import dev.velum.app.ui.recording.RecordingDetailScreen
import dev.velum.app.ui.theme.VelumBg
import dev.velum.app.ui.theme.VelumDivider
import dev.velum.app.ui.theme.VelumInkSecondary

enum class LibraryRailDestination {
    Library,
    Ask,
    Device,
}

/**
 * Compact: single-pane Files list (caller navigates to detail).
 * Expanded: NavigationRail + list + detail / Ask panes.
 */
@Composable
fun LibraryAdaptiveScreen(
    container: AppContainer,
    selectedRecordingId: String?,
    onSelectRecording: (String) -> Unit,
    onOpenRecordingCompact: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDevice: () -> Unit,
    onOpenAskCompact: () -> Unit,
    railDestination: LibraryRailDestination = LibraryRailDestination.Library,
    onRailDestination: (LibraryRailDestination) -> Unit = {},
    detailInitialTab: Int = 0,
) {
    val strings = LocalStrings.current
    WithWindowWidthSizeClass(Modifier.fillMaxSize()) { widthClass, _ ->
        val expanded = widthClass == WindowWidthSizeClass.Expanded
        if (!expanded) {
            when (railDestination) {
                LibraryRailDestination.Ask -> AskAcrossScreen(
                    container = container,
                    onBack = { onRailDestination(LibraryRailDestination.Library) },
                    onOpenCitation = { id, _ -> onOpenRecordingCompact(id) },
                )
                else -> FilesScreen(
                    container = container,
                    onOpenRecording = onOpenRecordingCompact,
                    onOpenSettings = onOpenSettings,
                    selectedRecordingId = selectedRecordingId,
                    onAskAcross = onOpenAskCompact,
                )
            }
            return@WithWindowWidthSizeClass
        }

        Row(Modifier.fillMaxSize().background(VelumBg)) {
            NavigationRail {
                NavigationRailItem(
                    selected = railDestination == LibraryRailDestination.Library,
                    onClick = { onRailDestination(LibraryRailDestination.Library) },
                    icon = { Icon(Icons.Outlined.Folder, contentDescription = strings.allFiles) },
                    label = { Text(strings.allFiles) },
                )
                NavigationRailItem(
                    selected = railDestination == LibraryRailDestination.Ask,
                    onClick = { onRailDestination(LibraryRailDestination.Ask) },
                    icon = { Icon(Icons.Outlined.TravelExplore, contentDescription = strings.ux.askAcrossTitle) },
                    label = { Text("Ask") },
                )
                NavigationRailItem(
                    selected = railDestination == LibraryRailDestination.Device,
                    onClick = {
                        onRailDestination(LibraryRailDestination.Device)
                        onOpenDevice()
                    },
                    icon = { Icon(Icons.Outlined.MicExternalOn, contentDescription = "Device") },
                    label = { Text("Device") },
                )
            }
            VerticalDivider(color = VelumDivider)
            Column(
                Modifier
                    .widthIn(min = 280.dp, max = 360.dp)
                    .width(320.dp)
                    .fillMaxHeight(),
            ) {
                FilesScreen(
                    container = container,
                    onOpenRecording = onSelectRecording,
                    onOpenSettings = onOpenSettings,
                    selectedRecordingId = selectedRecordingId,
                    onAskAcross = { onRailDestination(LibraryRailDestination.Ask) },
                )
            }
            VerticalDivider(color = VelumDivider)
            Column(Modifier.fillMaxSize()) {
                when {
                    railDestination == LibraryRailDestination.Ask -> AskAcrossScreen(
                        container = container,
                        onBack = { onRailDestination(LibraryRailDestination.Library) },
                        onOpenCitation = { id, _ -> onSelectRecording(id) },
                    )
                    selectedRecordingId != null -> RecordingDetailScreen(
                        container = container,
                        recordingId = selectedRecordingId,
                        onBack = { /* stay in pane */ },
                        initialTab = detailInitialTab,
                    )
                    else -> Box(
                        Modifier.fillMaxSize().background(VelumBg),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "Select a recording",
                            color = VelumInkSecondary,
                            fontSize = 16.sp,
                            modifier = Modifier.padding(24.dp),
                        )
                    }
                }
            }
        }
    }
}
