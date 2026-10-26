package dev.velum.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import dev.velum.app.i18n.LocalStrings
import dev.velum.app.ui.theme.VelumInk
import dev.velum.app.ui.theme.VelumInkSecondary
import dev.velum.app.ui.theme.VelumSurface

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchSheet(
    query: String,
    onQueryChange: (String) -> Unit,
    onDismiss: () -> Unit,
    resultCount: Int,
) {
    val strings = LocalStrings.current
    val focus = remember { FocusRequester() }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = VelumSurface,
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text(strings.search, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = VelumInk)
            Spacer(Modifier.height(12.dp))
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                cursorBrush = SolidColor(VelumInk),
                textStyle = TextStyle(color = VelumInk, fontSize = 17.sp),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus)
                    .padding(vertical = 8.dp),
                decorationBox = { inner ->
                    if (query.isEmpty()) {
                        Text(strings.searchHint, color = VelumInkSecondary, fontSize = 17.sp)
                    }
                    inner()
                },
            )
            ThinDivider(0)
            Spacer(Modifier.height(8.dp))
            Text(
                strings.searchResultsCount.replace("%d", resultCount.toString()),
                color = VelumInkSecondary,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
}
