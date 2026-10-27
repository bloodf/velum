package dev.velum.app.platform

import androidx.compose.runtime.Composable
import dev.velum.app.importing.AudioImportPipeline
import dev.velum.app.importing.ImportResult

/** SAF tree picker (USB OTG / SD / sync folder). No-op on desktop/iOS. */
@Composable
expect fun rememberImportFolderPicker(onPicked: (treeUri: String) -> Unit): () -> Unit

/** Scan all remembered trees and import new audio files (deduped). */
expect suspend fun syncWatchedFolders(pipeline: AudioImportPipeline): List<ImportResult>
