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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
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
    var showAdd by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    Box(Modifier.fillMaxSize().background(VelumBg)) {
        Column(Modifier.fillMaxSize()) {
            ScreenTopBar(strings.manageMemory, onBack = onBack)
            if (memories.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    EmptyState(strings.noMemoryYet, strings.noMemoryHint)
                }
            } else {
                LazyColumn(Modifier.weight(1f).padding(horizontal = 20.dp)) {
                    items(memories, key = { it.id }) { item ->
                        Text(
                            item.content,
                            color = VelumInk,
                            fontSize = 15.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 14.dp),
                        )
                    }
                }
            }
            BlackButton(
                strings.addMemory,
                { showAdd = true },
                Modifier.padding(20.dp),
            )
        }
    }

    if (showAdd) {
        ModalBottomSheet(
            onDismissRequest = { showAdd = false },
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
                        showAdd = false
                    },
                    onRight = {
                        scope.launch {
                            container.repository.addMemory("mem-${Random.nextInt(100000)}", draft.trim())
                            draft = ""
                            showAdd = false
                        }
                    },
                    rightEnabled = draft.isNotBlank(),
                )
            }
        }
    }
}
