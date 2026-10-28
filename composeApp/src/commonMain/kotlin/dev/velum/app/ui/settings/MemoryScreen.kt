package dev.velum.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.velum.app.di.AppContainer
import dev.velum.core.data.UserContentIds
import dev.velum.app.i18n.LocalStrings
import dev.velum.app.ui.components.BlackButton
import dev.velum.app.ui.components.DualActionRow
import dev.velum.app.ui.components.EmptyState
import dev.velum.app.ui.components.ScreenTopBar
import dev.velum.app.ui.theme.VelumBg
import dev.velum.app.ui.theme.VelumInk
import dev.velum.app.ui.theme.VelumInkSecondary
import dev.velum.app.ui.theme.VelumSurface
import kotlinx.coroutines.launch
import kotlin.random.Random

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryScreen(
    container: AppContainer,
    onBack: () -> Unit,
) {
    val strings = LocalStrings.current
    val memories by container.repository.observeMemories().collectAsState(emptyList())
    val glossary by container.repository.observeGlossary().collectAsState(emptyList())
    var showAddMemory by remember { mutableStateOf(false) }
    var showAddGlossary by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var term by remember { mutableStateOf("") }
    var definition by remember { mutableStateOf("") }
    var pendingDeleteMemoryId by remember { mutableStateOf<String?>(null) }
    var pendingDeleteGlossaryId by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Box(Modifier.fillMaxSize().background(VelumBg)) {
        Column(Modifier.fillMaxSize()) {
            ScreenTopBar(strings.manageMemory, onBack = onBack)
            LazyColumn(Modifier.weight(1f).padding(horizontal = 20.dp)) {
                item {
                    Spacer(Modifier.height(8.dp))
                    Text(strings.glossary, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = VelumInk)
                    Text(strings.glossaryHint, color = VelumInkSecondary, fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                }
                if (glossary.isEmpty()) {
                    item {
                        Text(strings.noMemoryYet, color = VelumInkSecondary, fontSize = 14.sp)
                        Spacer(Modifier.height(12.dp))
                    }
                } else {
                    items(glossary, key = { it.id }) { item ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable { pendingDeleteGlossaryId = item.id }
                                .padding(vertical = 10.dp),
                        ) {
                            Text(item.term, color = VelumInk, fontWeight = FontWeight.Medium, fontSize = 15.sp)
                            if (item.definition.isNotBlank()) {
                                Text(item.definition, color = VelumInkSecondary, fontSize = 13.sp)
                            }
                        }
                    }
                }
                item {
                    BlackButton(strings.addGlossaryTerm, { showAddGlossary = true })
                    Spacer(Modifier.height(24.dp))
                    Text(strings.memory, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = VelumInk)
                    Spacer(Modifier.height(8.dp))
                }
                if (memories.isEmpty()) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                            EmptyState(strings.noMemoryYet, strings.noMemoryHint)
                        }
                    }
                } else {
                    items(memories, key = { it.id }) { item ->
                        Text(
                            item.content,
                            color = VelumInk,
                            fontSize = 15.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { pendingDeleteMemoryId = item.id }
                                .padding(vertical = 14.dp),
                        )
                    }
                }
            }
            BlackButton(
                strings.addMemory,
                { showAddMemory = true },
                Modifier.padding(20.dp),
            )
        }
    }

    if (showAddMemory) {
        ModalBottomSheet(
            onDismissRequest = { showAddMemory = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = VelumSurface,
        ) {
            Column(Modifier.padding(bottom = 8.dp)) {
                Text(
                    strings.addMemory,
                    fontWeight = FontWeight.Thin,
                    fontSize = 26.sp,
                    color = VelumInk,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    strings.addMemoryHint,
                    color = VelumInkSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                Spacer(Modifier.height(12.dp))
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    textStyle = TextStyle(color = VelumInk, fontSize = 15.sp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .height(140.dp)
                        .border(1.dp, VelumInk, RoundedCornerShape(8.dp))
                        .padding(12.dp),
                    decorationBox = { inner ->
                        if (draft.isEmpty()) {
                            Text(strings.addMemoryPlaceholder, color = VelumInkSecondary, fontSize = 15.sp)
                        }
                        inner()
                    },
                )
                DualActionRow(
                    left = strings.cancel,
                    right = strings.save,
                    onLeft = {
                        draft = ""
                        showAddMemory = false
                    },
                    onRight = {
                        scope.launch {
                            container.repository.addMemory(UserContentIds.memory(), draft.trim())
                            draft = ""
                            showAddMemory = false
                        }
                    },
                    rightEnabled = draft.isNotBlank(),
                )
            }
        }
    }

    if (showAddGlossary) {
        ModalBottomSheet(
            onDismissRequest = { showAddGlossary = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = VelumSurface,
        ) {
            Column(Modifier.padding(bottom = 8.dp)) {
                Text(
                    strings.addGlossaryTerm,
                    fontWeight = FontWeight.Thin,
                    fontSize = 26.sp,
                    color = VelumInk,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                Spacer(Modifier.height(12.dp))
                BasicTextField(
                    value = term,
                    onValueChange = { term = it },
                    textStyle = TextStyle(color = VelumInk, fontSize = 15.sp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .border(1.dp, VelumInk, RoundedCornerShape(8.dp))
                        .padding(12.dp),
                    decorationBox = { inner ->
                        if (term.isEmpty()) Text(strings.glossaryTerm, color = VelumInkSecondary)
                        inner()
                    },
                )
                Spacer(Modifier.height(8.dp))
                BasicTextField(
                    value = definition,
                    onValueChange = { definition = it },
                    textStyle = TextStyle(color = VelumInk, fontSize = 15.sp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .height(80.dp)
                        .border(1.dp, VelumInk, RoundedCornerShape(8.dp))
                        .padding(12.dp),
                    decorationBox = { inner ->
                        if (definition.isEmpty()) Text(strings.glossaryDefinition, color = VelumInkSecondary)
                        inner()
                    },
                )
                DualActionRow(
                    left = strings.cancel,
                    right = strings.save,
                    onLeft = {
                        term = ""
                        definition = ""
                        showAddGlossary = false
                    },
                    onRight = {
                        scope.launch {
                            container.repository.addGlossaryTerm(
                                UserContentIds.glossary(),
                                term.trim(),
                                definition.trim(),
                            )
                            term = ""
                            definition = ""
                            showAddGlossary = false
                        }
                    },
                    rightEnabled = term.isNotBlank(),
                )
            }
        }
    }


    pendingDeleteMemoryId?.let { id ->
        AlertDialog(
            onDismissRequest = { pendingDeleteMemoryId = null },
            title = { Text(strings.ux.confirmDeleteTitle) },
            text = { Text(strings.ux.confirmDeleteMemoryBody) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDeleteMemoryId = null
                        scope.launch { container.repository.deleteMemory(id) }
                    },
                ) { Text(strings.delete) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteMemoryId = null }) { Text(strings.cancel) }
            },
        )
    }
    pendingDeleteGlossaryId?.let { id ->
        AlertDialog(
            onDismissRequest = { pendingDeleteGlossaryId = null },
            title = { Text(strings.ux.confirmDeleteTitle) },
            text = { Text(strings.ux.confirmDeleteMemoryBody) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDeleteGlossaryId = null
                        scope.launch { container.repository.deleteGlossaryTerm(id) }
                    },
                ) { Text(strings.delete) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteGlossaryId = null }) { Text(strings.cancel) }
            },
        )
    }
}
