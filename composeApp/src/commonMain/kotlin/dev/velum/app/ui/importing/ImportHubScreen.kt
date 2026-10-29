package dev.velum.app.ui.importing

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.velum.app.di.AppContainer
import dev.velum.app.i18n.LocalStrings
import dev.velum.app.importing.AudioImportPipeline
import dev.velum.app.importing.ImportResult
import dev.velum.app.platform.HapticKind
import dev.velum.app.platform.LocalHaptics
import dev.velum.app.platform.clearWatchedFolders
import dev.velum.app.platform.rememberAudioImporter
import dev.velum.app.platform.watchedFolderCount
import dev.velum.app.ui.components.BlackButton
import dev.velum.app.ui.components.OutlineButton
import dev.velum.app.ui.components.ScreenTopBar
import dev.velum.app.ui.theme.VelumBg
import dev.velum.app.ui.theme.VelumInk
import dev.velum.app.ui.theme.VelumInkSecondary
import kotlinx.coroutines.launch

/**
 * External recorder import hub: SAF file picker + watched folder (USB/SD/sync).
 * Platform-specific folder picker is injected so commonMain stays free of Android APIs.
 */
@Composable
fun ImportHubScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onOpenRecording: (String) -> Unit,
    onPickWatchedFolder: (() -> Unit)? = null,
    onSyncWatchedFolder: (suspend () -> List<ImportResult>)? = null,
) {
    val strings = LocalStrings.current
    val haptics = LocalHaptics.current
    val scope = rememberCoroutineScope()
    val pipeline = remember(container) { AudioImportPipeline(container) }
    var status by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    var confirmClearFolders by remember { mutableStateOf(false) }
    val folderCount = watchedFolderCount()
    val pickFile = rememberAudioImporter { path ->
        scope.launch {
            importing = true
            status = strings.ux.importing
            when (val r = pipeline.importFile(path, source = "import")) {
                is ImportResult.Created -> {
                    status = strings.ux.imported
                    onOpenRecording(r.recordingId)
                }
                is ImportResult.Duplicate -> {
                    status = strings.ux.importDuplicate
                    onOpenRecording(r.existingRecordingId)
                }
                is ImportResult.Failed -> status = strings.ux.genericError
            }
            importing = false
        }
    }

    if (confirmClearFolders) {
        AlertDialog(
            onDismissRequest = { confirmClearFolders = false },
            title = { Text(strings.ux.confirmClearTitle) },
            text = { Text(strings.ux.confirmClearWatchedFoldersBody) },
            confirmButton = {
                TextButton(
                    onClick = {
                        haptics.perform(HapticKind.Medium)
                        confirmClearFolders = false
                        clearWatchedFolders()
                        status = strings.ux.watchedFoldersCleared
                    },
                ) { Text(strings.confirm) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearFolders = false }) { Text(strings.cancel) }
            },
        )
    }

    // Pin ScreenTopBar outside the scroll so Back stays reachable (#118).
    Column(
        Modifier
            .fillMaxSize()
            .background(VelumBg),
    ) {
        ScreenTopBar(strings.importAudio, onBack = onBack)
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(
                    strings.ux.importHubTitle,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp,
                    color = VelumInk,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    strings.ux.importHubBody,
                    color = VelumInkSecondary,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                )
                Spacer(Modifier.height(20.dp))
                BlackButton(
                    strings.ux.chooseAudioFile,
                    pickFile,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !importing,
                )
                Spacer(Modifier.height(12.dp))
                if (onPickWatchedFolder != null) {
                    OutlineButton(
                        strings.ux.pickWatchedFolder,
                        onPickWatchedFolder,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !importing,
                    )
                    Spacer(Modifier.height(12.dp))
                }
                if (onSyncWatchedFolder != null) {
                    OutlineButton(
                        strings.ux.syncWatchedFolders,
                        {
                            scope.launch {
                                importing = true
                                status = strings.ux.scanning
                                val results = onSyncWatchedFolder()
                                val created = results.count { it is ImportResult.Created }
                                val dupes = results.count { it is ImportResult.Duplicate }
                                val failed = results.count { it is ImportResult.Failed }
                                status = strings.ux.syncDoneFormat.format(created, dupes, failed)
                                results.filterIsInstance<ImportResult.Created>().lastOrNull()
                                    ?.let { onOpenRecording(it.recordingId) }
                                importing = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !importing,
                    )
                    Spacer(Modifier.height(12.dp))
                }
                if (folderCount > 0) {
                    OutlineButton(
                        strings.ux.clearWatchedFolders,
                        { confirmClearFolders = true },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !importing,
                    )
                    Spacer(Modifier.height(12.dp))
                }
                if (importing) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                }
                status?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = VelumInkSecondary, fontSize = 13.sp)
                }
            }
        }
    }
}
