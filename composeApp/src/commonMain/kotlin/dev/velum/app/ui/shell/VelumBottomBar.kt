package dev.velum.app.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.velum.app.ui.theme.VelumBlack
import dev.velum.app.ui.theme.VelumDivider
import dev.velum.app.ui.theme.VelumInkSecondary
import dev.velum.app.ui.theme.VelumSurface

enum class ShellTab { Files, Explore }

@Composable
fun VelumBottomBar(
    selected: ShellTab,
    fabOpen: Boolean,
    onFiles: () -> Unit,
    onExplore: () -> Unit,
    onFab: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxWidth().background(VelumSurface)) {
        HorizontalDivider(thickness = 0.5.dp, color = VelumDivider)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(horizontal = 48.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (selected == ShellTab.Files && !fabOpen) Icons.Filled.Folder else Icons.Outlined.Folder,
                contentDescription = "Files",
                tint = if (selected == ShellTab.Files && !fabOpen) VelumBlack else VelumInkSecondary,
                modifier = Modifier.size(26.dp).clickable(onClick = onFiles),
            )
            SpacerFab()
            Icon(
                if (selected == ShellTab.Explore && !fabOpen) Icons.Filled.Apps else Icons.Outlined.Apps,
                contentDescription = "Explore",
                tint = if (selected == ShellTab.Explore && !fabOpen) VelumBlack else VelumInkSecondary,
                modifier = Modifier.size(26.dp).clickable(onClick = onExplore),
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = (-18).dp)
                .size(58.dp)
                .clip(CircleShape)
                .background(VelumBlack)
                .border(3.dp, VelumSurface, CircleShape)
                .clickable(onClick = onFab),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (fabOpen) Icons.Outlined.Close else Icons.Outlined.AutoAwesome,
                contentDescription = if (fabOpen) "Close" else "Create",
                tint = Color.White,
                modifier = Modifier.size(26.dp),
            )
        }
    }
}

@Composable
private fun SpacerFab() {
    Box(Modifier.size(58.dp)) {}
}
