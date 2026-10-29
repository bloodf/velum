package dev.velum.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.velum.app.di.AppContainer
import dev.velum.app.i18n.LocalStrings
import dev.velum.app.ui.components.ChevronRow
import dev.velum.app.ui.components.ScreenTopBar
import dev.velum.app.ui.components.SuggestionChip
import dev.velum.app.ui.components.ToggleRow
import dev.velum.app.ui.theme.VelumDivider
import dev.velum.app.ui.theme.VelumInk
import dev.velum.app.ui.theme.VelumInkSecondary
import dev.velum.app.ui.theme.VelumSurface
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@OptIn(FlowPreview::class)
@Composable
fun PersonalizationScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onMemory: () -> Unit,
) {
    val strings = LocalStrings.current
    val prefs by container.settingsRepository.preferences.collectAsState()
    val scope = rememberCoroutineScope()
    var displayName by remember { mutableStateOf(prefs.displayName) }
    var focus by remember { mutableStateOf(prefs.contentFocus) }
    var instructions by remember { mutableStateOf(prefs.customInstructions) }

    LaunchedEffect(prefs.displayName, prefs.contentFocus, prefs.customInstructions) {
        displayName = prefs.displayName
        focus = prefs.contentFocus
        instructions = prefs.customInstructions
    }

    LaunchedEffect(Unit) {
        snapshotFlow { Triple(displayName, focus, instructions) }
            .debounce(400)
            .distinctUntilChanged()
            .collect { (name, contentFocus, custom) ->
                container.settingsRepository.setDisplayName(name)
                container.settingsRepository.setContentFocus(contentFocus)
                container.settingsRepository.setCustomInstructions(custom)
            }
    }

    // Pin ScreenTopBar outside the scroll so Back stays reachable (#118).
    Column(
        Modifier
            .fillMaxSize()
            .background(VelumSurface),
    ) {
        ScreenTopBar(strings.personalization, onBack = onBack)
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            // Profile fields live on this screen (display name / focus / instructions).
            Spacer(Modifier.height(8.dp))
            SectionTitle(strings.displayName)
            HintField(displayName, strings.displayName) { displayName = it }
            Spacer(Modifier.height(16.dp))
            SectionTitle(strings.contentFocus)
            HintField(focus, strings.contentFocusHint) { focus = it }
            Spacer(Modifier.height(10.dp))
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SuggestionChip(strings.chipKeyPoints) { focus = strings.chipKeyPoints }
            }
            Spacer(Modifier.height(8.dp))
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SuggestionChip(strings.chipRisks) { focus = strings.chipRisks }
                SuggestionChip(strings.chipActions) { focus = strings.chipActions }
            }
            Spacer(Modifier.height(20.dp))
            SectionTitle(strings.customInstructions)
            HintField(instructions, strings.customInstructionsHint) { instructions = it }
            Spacer(Modifier.height(10.dp))
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SuggestionChip(strings.chipDirect) { instructions = strings.chipDirect }
            }
            Spacer(Modifier.height(8.dp))
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SuggestionChip(strings.chipFormal) { instructions = strings.chipFormal }
                SuggestionChip(strings.chipStructured) { instructions = strings.chipStructured }
            }
            Spacer(Modifier.height(24.dp))
            Text(
                strings.memory,
                color = VelumInkSecondary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Spacer(Modifier.height(4.dp))
            ToggleRow(
                strings.useSavedMemory,
                prefs.useSavedMemory,
                { enabled -> scope.launch { container.settingsRepository.setUseSavedMemory(enabled) } },
                strings.useMemoryHint,
            )
            ChevronRow(strings.manageMemory, onMemory, showDivider = false)
            Text(
                strings.manageMemoryHint,
                color = VelumInkSecondary,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        color = VelumInk,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
    )
}

@Composable
private fun HintField(value: String, hint: String, onChange: (String) -> Unit) {
    BasicTextField(
        value = value,
        onValueChange = onChange,
        textStyle = TextStyle(color = VelumInk, fontSize = 15.sp, lineHeight = 22.sp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .height(110.dp)
            .border(1.dp, VelumDivider, RoundedCornerShape(10.dp))
            .padding(14.dp),
        decorationBox = { inner ->
            if (value.isEmpty()) {
                Text(hint, color = VelumInkSecondary, fontSize = 15.sp, lineHeight = 22.sp)
            }
            inner()
        },
    )
}
