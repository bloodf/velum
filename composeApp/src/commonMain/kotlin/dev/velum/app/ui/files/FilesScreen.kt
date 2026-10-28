package dev.velum.app.ui.files

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.MicExternalOn
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.velum.app.di.AppContainer
import dev.velum.app.i18n.LocalStrings
import dev.velum.app.importing.AudioImportPipeline
import dev.velum.app.platform.HapticKind
import dev.velum.app.platform.LocalHaptics
import dev.velum.app.platform.shareText
import dev.velum.app.platform.syncWatchedFolders
import dev.velum.app.ui.components.DisplayTitle
import dev.velum.app.ui.components.EmptyState
import dev.velum.app.ui.components.SearchSheet
import dev.velum.app.ui.components.ThinDivider
import dev.velum.app.ui.theme.VelumBg
import dev.velum.app.ui.theme.VelumInk
import dev.velum.app.ui.theme.VelumInkSecondary
import dev.velum.app.ui.theme.VelumOnWarningContainer
import dev.velum.app.ui.theme.VelumSurface
import dev.velum.app.ui.theme.VelumWarningContainer
import dev.velum.core.db.Recording
import dev.velum.core.search.FtsQuery
import dev.velum.core.search.RecordingSearch
import dev.velum.core.time.VelumDateTime
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FilesScreen(
    container: AppContainer,
    onOpenRecording: (String) -> Unit,
    onOpenSettings: () -> Unit,
    selectedRecordingId: String? = null,
    onAskAcross: (() -> Unit)? = null,
    /** Pre-fill search (marketing / desktop library shot with filtered list). */
    initialSearchQuery: String = "",
) {
    val strings = LocalStrings.current
    val haptics = LocalHaptics.current
    val scope = rememberCoroutineScope()
    val recordings by container.repository.observeRecordings().collectAsState(emptyList())
    val trashed by container.repository.observeTrashed().collectAsState(emptyList())
    val importPipeline = remember(container) { AudioImportPipeline(container) }
    var showFilter by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf(initialSearchQuery) }
    var libraryFilter by remember { mutableStateOf(LibraryFilter.All) }
    var librarySort by remember { mutableStateOf(LibrarySort.Newest) }
    var searchError by remember { mutableStateOf(false) }
    var ftsIds by remember { mutableStateOf<List<String>?>(null) }
    var refreshEpoch by remember { mutableStateOf(0) }
    var isRefreshing by remember { mutableStateOf(false) }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    var confirmTrash by remember { mutableStateOf(false) }
    var confirmDeleteForever by remember { mutableStateOf(false) }
    var confirmEmptyTrash by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<Recording?>(null) }

    val baseList = when (libraryFilter) {
        LibraryFilter.All -> recordings
        LibraryFilter.Trash -> trashed
        LibraryFilter.NotePro -> recordings.filter { it.source == "note_pro" }
    }

    LaunchedEffect(baseList, searchQuery, refreshEpoch) {
        searchError = false
        val match = FtsQuery.fromUserInput(searchQuery)
        ftsIds = if (match != null) {
            try {
                container.repository.searchRecordingIdsFts(match)
            } catch (_: Throwable) {
                searchError = true
                null
            }
        } else {
            null
        }
    }

    val filtered = if (searchQuery.isBlank()) {
        baseList
    } else {
        RecordingSearch.recordings(baseList, searchQuery, ftsIds)
    }
    val visible = remember(filtered, librarySort) {
        LibraryListLogic.sorted(filtered, librarySort)
    }

    fun exitSelection() {
        selectionMode = false
        selectedIds = emptySet()
    }

    fun enterSelection(id: String) {
        selectionMode = true
        selectedIds = setOf(id)
    }

    fun toggleSelected(id: String) {
        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
        if (selectedIds.isEmpty()) selectionMode = false
    }

    fun refreshLibrary() {
        if (isRefreshing) return
        scope.launch {
            isRefreshing = true
            try {
                refreshEpoch += 1
                runCatching { syncWatchedFolders(importPipeline) }
            } finally {
                isRefreshing = false
            }
        }
    }

    fun shareSelected() {
        val picked = visible.filter { it.id in selectedIds }
        if (picked.isEmpty()) return
        val body = picked.joinToString("\n---\n\n") { rec ->
            LibraryListLogic.recordingMarkdown(
                title = rec.title,
                transcriptionLabel = strings.transcription,
                summaryLabel = strings.summary,
                transcript = rec.transcript,
                summaryMarkdown = rec.summary_markdown,
            )
        }
        val shareTitle = if (picked.size == 1) picked.first().title else strings.allFiles
        shareText(shareTitle, body)
        exitSelection()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(VelumBg),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectionMode) {
                HeaderIcon(Icons.Outlined.Close, strings.ux.exitSelection) { exitSelection() }
                Text(
                    strings.ux.selectionCount.replace("%d", selectedIds.size.toString()),
                    color = VelumInk,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                if (selectedIds.size == 1) {
                    val only = visible.firstOrNull { it.id in selectedIds }
                    if (only != null) {
                        HeaderIcon(Icons.Outlined.DriveFileRenameOutline, strings.ux.rename) {
                            renameTarget = only
                        }
                    }
                }
                HeaderIcon(Icons.Outlined.IosShare, strings.share) { shareSelected() }
                when (libraryFilter) {
                    LibraryFilter.Trash -> {
                        HeaderIcon(Icons.Outlined.RestoreFromTrash, strings.ux.restore) {
                            scope.launch {
                                selectedIds.forEach { container.repository.restoreRecording(it) }
                                exitSelection()
                            }
                        }
                        HeaderIcon(Icons.Outlined.DeleteForever, strings.ux.deleteForever) {
                            confirmDeleteForever = true
                        }
                    }
                    LibraryFilter.All, LibraryFilter.NotePro -> {
                        HeaderIcon(Icons.Outlined.Delete, strings.moveToTrash) {
                            confirmTrash = true
                        }
                    }
                }
            } else {
                Spacer(Modifier.weight(1f))
                HeaderIcon(Icons.Outlined.Refresh, strings.ux.refreshLibrary) { refreshLibrary() }
                HeaderIcon(Icons.Outlined.TravelExplore, strings.search) { showSearch = true }
                if (onAskAcross != null) {
                    HeaderIcon(Icons.Outlined.AutoAwesome, strings.ux.askAcrossShort, onAskAcross)
                }
                if (libraryFilter == LibraryFilter.Trash && trashed.isNotEmpty()) {
                    HeaderIcon(Icons.Outlined.DeleteSweep, strings.ux.emptyTrash) {
                        confirmEmptyTrash = true
                    }
                }
                HeaderIcon(Icons.Outlined.Settings, strings.settings, onOpenSettings)
            }
        }

        Spacer(Modifier.height(8.dp))
        DisplayTitle(
            text = when (libraryFilter) {
                LibraryFilter.All -> strings.allFiles
                LibraryFilter.Trash -> strings.trash
                LibraryFilter.NotePro -> strings.ux.sourceNotePro
            },
            trailing = {
                Box(
                    Modifier
                        .defaultMinSize(48.dp, 48.dp)
                        .clickable { showFilter = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.ExpandMore,
                        contentDescription = strings.filterSort,
                        tint = VelumInkSecondary,
                        modifier = Modifier.size(28.dp),
                    )
                }
            },
        )
        if (searchError) {
            Text(
                strings.ux.searchUnavailable,
                color = VelumInkSecondary,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }
        if (searchQuery.isNotBlank()) {
            Text(
                strings.searchResultsCount.replace("%d", visible.size.toString()),
                color = VelumInkSecondary,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        ThinDivider(0)

        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { refreshLibrary() },
            modifier = Modifier.fillMaxSize(),
        ) {
            if (visible.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val (title, subtitle) = when (libraryFilter) {
                        LibraryFilter.All -> strings.noRecordingsYet to strings.ux.noRecordingsHint
                        LibraryFilter.Trash -> strings.ux.trashEmptyTitle to strings.ux.trashEmptyHint
                        LibraryFilter.NotePro -> strings.ux.noteProEmptyTitle to strings.ux.noteProEmptyHint
                    }
                    EmptyState(title = title, subtitle = subtitle)
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(visible, key = { it.id }) { rec ->
                        RecordingRow(
                            rec = rec,
                            selected = rec.id == selectedRecordingId,
                            selectionMode = selectionMode,
                            checked = rec.id in selectedIds,
                            searchQuery = searchQuery,
                            onClick = {
                                if (selectionMode) {
                                    toggleSelected(rec.id)
                                } else if (libraryFilter == LibraryFilter.Trash) {
                                    scope.launch {
                                        container.repository.restoreRecording(rec.id)
                                        onOpenRecording(rec.id)
                                    }
                                } else {
                                    onOpenRecording(rec.id)
                                }
                            },
                            onLongClick = {
                                if (selectionMode) toggleSelected(rec.id) else enterSelection(rec.id)
                            },
                        )
                    }
                }
            }
        }
    }

    if (showFilter) {
        ModalBottomSheet(
            onDismissRequest = { showFilter = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = VelumSurface,
        ) {
            FilterSortSheet(
                allCount = recordings.size,
                trashCount = trashed.size,
                noteProCount = recordings.count { it.source == "note_pro" },
                selectedFilter = libraryFilter,
                selectedSort = librarySort,
                onSelectFilter = { filter ->
                    libraryFilter = filter
                    exitSelection()
                    showFilter = false
                },
                onSelectSort = { sort ->
                    librarySort = sort
                    showFilter = false
                },
            )
        }
    }
    if (showSearch) {
        SearchSheet(
            query = searchQuery,
            onQueryChange = { searchQuery = it },
            onDismiss = { showSearch = false },
            resultCount = visible.size,
        )
    }
    if (confirmTrash) {
        AlertDialog(
            onDismissRequest = { confirmTrash = false },
            title = { Text(strings.moveToTrash) },
            text = { Text(strings.ux.trashConfirmBody) },
            confirmButton = {
                TextButton(
                    onClick = {
                        haptics.perform(HapticKind.Medium)
                        confirmTrash = false
                        scope.launch {
                            selectedIds.forEach { container.repository.trashRecording(it) }
                            exitSelection()
                        }
                    },
                ) { Text(strings.confirm) }
            },
            dismissButton = {
                TextButton(onClick = { confirmTrash = false }) { Text(strings.cancel) }
            },
        )
    }
    if (confirmDeleteForever) {
        AlertDialog(
            onDismissRequest = { confirmDeleteForever = false },
            title = { Text(strings.ux.deleteForever) },
            text = { Text(strings.ux.deleteForeverBody) },
            confirmButton = {
                TextButton(
                    onClick = {
                        haptics.perform(HapticKind.Medium)
                        confirmDeleteForever = false
                        scope.launch {
                            selectedIds.forEach { container.repository.deleteRecordingHard(it) }
                            exitSelection()
                        }
                    },
                ) { Text(strings.confirm) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteForever = false }) { Text(strings.cancel) }
            },
        )
    }
    if (confirmEmptyTrash) {
        AlertDialog(
            onDismissRequest = { confirmEmptyTrash = false },
            title = { Text(strings.ux.emptyTrash) },
            text = { Text(strings.ux.emptyTrashBody) },
            confirmButton = {
                TextButton(
                    onClick = {
                        haptics.perform(HapticKind.Medium)
                        confirmEmptyTrash = false
                        scope.launch {
                            trashed.forEach { container.repository.deleteRecordingHard(it.id) }
                            exitSelection()
                        }
                    },
                ) { Text(strings.confirm) }
            },
            dismissButton = {
                TextButton(onClick = { confirmEmptyTrash = false }) { Text(strings.cancel) }
            },
        )
    }
    renameTarget?.let { target ->
        RenameRecordingDialog(
            initialTitle = target.title,
            onDismiss = { renameTarget = null },
            onConfirm = { newTitle ->
                val trimmed = newTitle.trim()
                renameTarget = null
                if (trimmed.isNotEmpty() && trimmed != target.title) {
                    scope.launch {
                        container.repository.updateRecordingTitle(target.id, trimmed)
                    }
                }
                exitSelection()
            },
        )
    }
}

@Composable
private fun HeaderIcon(
    imageVector: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .defaultMinSize(48.dp, 48.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector,
            contentDescription = contentDescription,
            tint = VelumInk,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun RenameRecordingDialog(
    initialTitle: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val strings = LocalStrings.current
    var draft by remember(initialTitle) { mutableStateOf(initialTitle) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.ux.renameRecording) },
        text = {
            BasicTextField(
                value = draft,
                onValueChange = { draft = it },
                singleLine = true,
                cursorBrush = SolidColor(VelumInk),
                textStyle = TextStyle(color = VelumInk, fontSize = 16.sp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(draft) }) { Text(strings.save) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(strings.cancel) }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecordingRow(
    rec: Recording,
    selected: Boolean = false,
    selectionMode: Boolean,
    checked: Boolean,
    searchQuery: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val strings = LocalStrings.current
    val mins = (rec.duration_seconds / 60).toInt()
    val timeLabel = VelumDateTime.formatClock(rec.started_at)
    val deviceLabel = when (rec.source) {
        "note_pro" -> strings.ux.sourceNotePro
        "phone" -> strings.ux.sourcePhone
        "import" -> strings.ux.sourceImport
        else -> null
    }
    val highlightBg = VelumWarningContainer
    val highlightFg = VelumOnWarningContainer
    val titleAnnotated = remember(rec.title, searchQuery, highlightBg, highlightFg) {
        LibraryListLogic.highlightedTitle(
            title = rec.title,
            query = searchQuery,
            highlightBackground = highlightBg,
            highlightForeground = highlightFg,
        )
    }
    val sourceIcon = if (rec.source == "note_pro") Icons.Outlined.MicExternalOn else Icons.Outlined.Headphones
    val sourceA11y = if (rec.source == "note_pro") strings.ux.noteProMicA11y else strings.ux.headphonesA11y
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .background(if (selected || checked) VelumSurface else VelumBg)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionMode) {
            Checkbox(
                checked = checked,
                onCheckedChange = { onClick() },
            )
            Spacer(Modifier.width(8.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = titleAnnotated,
                color = VelumInk,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$timeLabel | $mins min", color = VelumInkSecondary, fontSize = 13.sp)
                Spacer(Modifier.width(8.dp))
                Icon(
                    sourceIcon,
                    contentDescription = sourceA11y,
                    tint = VelumInkSecondary,
                    modifier = Modifier.size(14.dp),
                )
                if (deviceLabel != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        deviceLabel,
                        color = VelumInkSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

private enum class LibraryFilter {
    All,
    Trash,
    NotePro,
}

@Composable
private fun FilterSortSheet(
    allCount: Int,
    trashCount: Int,
    noteProCount: Int,
    selectedFilter: LibraryFilter,
    selectedSort: LibrarySort,
    onSelectFilter: (LibraryFilter) -> Unit,
    onSelectSort: (LibrarySort) -> Unit,
) {
    val strings = LocalStrings.current
    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text(strings.filterSort, fontWeight = FontWeight.Thin, fontSize = 28.sp, color = VelumInk)
        Spacer(Modifier.height(16.dp))
        ThinDivider(0)
        FilterLine(strings.allFiles, "$allCount", selectedFilter == LibraryFilter.All) {
            onSelectFilter(LibraryFilter.All)
        }
        FilterLine(strings.trash, "$trashCount", selectedFilter == LibraryFilter.Trash) {
            onSelectFilter(LibraryFilter.Trash)
        }
        FilterLine(strings.ux.sourceNotePro, "$noteProCount", selectedFilter == LibraryFilter.NotePro) {
            onSelectFilter(LibraryFilter.NotePro)
        }
        Spacer(Modifier.height(24.dp))
        Text(strings.sortByCreated, fontWeight = FontWeight.Medium, fontSize = 15.sp, color = VelumInkSecondary)
        Spacer(Modifier.height(4.dp))
        ThinDivider(0)
        FilterLine(strings.ux.sortNewest, "", selectedSort == LibrarySort.Newest) {
            onSelectSort(LibrarySort.Newest)
        }
        FilterLine(strings.ux.sortOldest, "", selectedSort == LibrarySort.Oldest) {
            onSelectSort(LibrarySort.Oldest)
        }
        FilterLine(strings.ux.sortTitle, "", selectedSort == LibrarySort.TitleAsc) {
            onSelectSort(LibrarySort.TitleAsc)
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun FilterLine(
    label: String,
    count: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = VelumInk, fontSize = 16.sp)
        Text(
            when {
                selected && count.isNotEmpty() -> "✓  $count"
                selected -> "✓"
                else -> count
            },
            color = if (selected) VelumInk else VelumInkSecondary,
            fontSize = 15.sp,
        )
    }
    ThinDivider(0)
}
