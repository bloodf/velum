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
import androidx.compose.material3.Text
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
import dev.velum.app.platform.rememberAudioImporter
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
    val scope = rememberCoroutineScope()
    val pipeline = remember(container) { AudioImportPipeline(container) }
    var status by remember { mutableStateOf<String?>(null) }
    val pickFile = rememberAudioImporter { path ->
        scope.launch {
            status = "Importing…"
            when (val r = pipeline.importFile(path, source = "import")) {
                is ImportResult.Created -> {
                    status = "Imported"
                    onOpenRecording(r.recordingId)
                }
                is ImportResult.Duplicate -> {
                    status = "Duplicate — opened existing"
                    onOpenRecording(r.existingRecordingId)
                }
                is ImportResult.Failed -> status = r.message
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(VelumBg)
            .verticalScroll(rememberScrollState()),
    ) {
        ScreenTopBar(strings.importAudio, onBack = onBack)
        Column(Modifier.padding(20.dp)) {
            Text(
                "Any external recorder",
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
                color = VelumInk,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "mp3, m4a/aac, wav, ogg/opus, flac, amr, 3gp, webm, mp4 audio — normalized to PCM for the same pipeline as Plaud sync.",
                color = VelumInkSecondary,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )
            Spacer(Modifier.height(20.dp))
            BlackButton("Choose audio file", pickFile, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            if (onPickWatchedFolder != null) {
                OutlineButton(
                    "Pick USB / SD / sync folder",
                    onPickWatchedFolder,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
            }
            if (onSyncWatchedFolder != null) {
                OutlineButton(
                    "Re-sync watched folders",
                    {
                        scope.launch {
                            status = "Scanning…"
                            val results = onSyncWatchedFolder()
                            val created = results.count { it is ImportResult.Created }
                            val dupes = results.count { it is ImportResult.Duplicate }
                            val failed = results.count { it is ImportResult.Failed }
                            status = "Sync done · $created new · $dupes dupes · $failed failed"
                            results.filterIsInstance<ImportResult.Created>().lastOrNull()
                                ?.let { onOpenRecording(it.recordingId) }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
            }
            status?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = VelumInkSecondary, fontSize = 13.sp)
            }
        }
    }
}
