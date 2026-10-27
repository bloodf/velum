package dev.velum.app.ui.files

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.MicExternalOn
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.velum.app.di.AppContainer
import dev.velum.app.i18n.LocalStrings
import dev.velum.app.ui.components.DisplayTitle
import dev.velum.app.ui.components.EmptyState
import dev.velum.app.ui.components.SearchSheet
import dev.velum.app.ui.components.ThinDivider
import dev.velum.app.ui.theme.VelumBg
import dev.velum.app.ui.theme.VelumInk
import dev.velum.app.ui.theme.VelumInkSecondary
import dev.velum.app.ui.theme.VelumSurface
import dev.velum.core.db.Recording
import dev.velum.core.search.FtsQuery
import dev.velum.core.search.RecordingSearch
import dev.velum.core.time.VelumDateTime

@OptIn(ExperimentalMaterial3Api::class)
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
    val recordings by container.repository.observeRecordings().collectAsState(emptyList())
    var showFilter by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf(initialSearchQuery) }
    // FTS hits for non-blank queries; blank search shows [recordings] immediately
    // (avoids an empty first frame in ImageComposeScene / Roborazzi).
    var ftsIds by remember { mutableStateOf<List<String>?>(null) }

    LaunchedEffect(recordings, searchQuery) {
        val match = FtsQuery.fromUserInput(searchQuery)
        ftsIds = if (match != null) {
            try {
                container.repository.searchRecordingIdsFts(match)
            } catch (_: Throwable) {
                null
            }
        } else {
            null
        }
    }
    val visible = if (searchQuery.isBlank()) {
        recordings
    } else {
        RecordingSearch.recordings(recordings, searchQuery, ftsIds)
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
            Icon(Icons.Outlined.MicExternalOn, null, tint = VelumInk, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Outlined.Sync, null, tint = VelumInkSecondary, modifier = Modifier.size(16.dp))
            Icon(Icons.Outlined.ExpandMore, null, tint = VelumInkSecondary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.weight(1f))
            Box(
                Modifier
                    .defaultMinSize(48.dp, 48.dp)
                    .clickable { showSearch = true },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.TravelExplore,
                    contentDescription = strings.search,
                    tint = VelumInk,
                    modifier = Modifier.size(22.dp),
                )
            }
            if (onAskAcross != null) {
                Box(
                    Modifier
                        .defaultMinSize(48.dp, 48.dp)
                        .clickable(onClick = onAskAcross),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.AutoAwesome,
                        contentDescription = strings.ux.askAcrossShort,
                        tint = VelumInk,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            Box(
                modifier = Modifier
                    .defaultMinSize(48.dp, 48.dp)
                    .clickable(onClick = onOpenSettings),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Settings,
                    contentDescription = strings.settings,
                    tint = VelumInk,
                    modifier = Modifier.size(22.dp),
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        DisplayTitle(
            text = strings.allFiles,
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

        if (visible.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    title = strings.noRecordingsYet,
                    subtitle = strings.ux.noRecordingsHint,
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(visible, key = { it.id }) { rec ->
                    RecordingRow(
                        rec = rec,
                        selected = rec.id == selectedRecordingId,
                    ) { onOpenRecording(rec.id) }
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
                total = recordings.size,
                onClose = { showFilter = false },
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
}

@Composable
private fun RecordingRow(rec: Recording, selected: Boolean = false, onClick: () -> Unit) {
    val strings = LocalStrings.current
    val mins = (rec.duration_seconds / 60).toInt()
    val timeLabel = VelumDateTime.formatClock(rec.started_at)
    val deviceLabel = when (rec.source) {
        "note_pro" -> strings.ux.sourceNotePro
        "phone" -> strings.ux.sourcePhone
        "import" -> strings.ux.sourceImport
        else -> null
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) VelumSurface else VelumBg)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Text(rec.title, color = VelumInk, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("$timeLabel | $mins min", color = VelumInkSecondary, fontSize = 13.sp)
            Spacer(Modifier.width(8.dp))
            Icon(
                if (rec.source == "note_pro") Icons.Outlined.MicExternalOn else Icons.Outlined.Headphones,
                null,
                tint = VelumInkSecondary,
                modifier = Modifier.size(14.dp),
            )
            if (deviceLabel != null) {
                Spacer(Modifier.width(8.dp))
                Text(deviceLabel, color = VelumInkSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun FilterSortSheet(total: Int, onClose: () -> Unit) {
    val strings = LocalStrings.current
    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text(strings.filterSort, fontWeight = FontWeight.Thin, fontSize = 28.sp, color = VelumInk)
        Spacer(Modifier.height(20.dp))
        Text(strings.sortByCreated, fontSize = 16.sp, color = VelumInk)
        Spacer(Modifier.height(16.dp))
        ThinDivider(0)
        FilterLine(strings.allFiles, "$total", selected = true)
        FilterLine(strings.unclassified, "0", selected = false)
        FilterLine(strings.trash, "0", selected = false)
        Spacer(Modifier.height(12.dp))
        Text(strings.folders, fontWeight = FontWeight.Medium, fontSize = 15.sp)
        Spacer(Modifier.height(8.dp))
        Text(strings.comesFrom, fontWeight = FontWeight.Medium, fontSize = 15.sp)
        FilterLine(strings.ux.sourceNotePro, "1", selected = false)
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun FilterLine(label: String, count: String, selected: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = VelumInk, fontSize = 16.sp)
        Text(
            if (selected) "✓  $count" else count,
            color = if (selected) VelumInk else VelumInkSecondary,
            fontSize = 15.sp,
        )
    }
    ThinDivider(0)
}

