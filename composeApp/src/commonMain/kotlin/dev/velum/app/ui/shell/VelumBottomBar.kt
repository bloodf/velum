package dev.velum.app.ui.shell

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.dp
import dev.velum.app.i18n.LocalStrings
import dev.velum.app.platform.HapticKind
import dev.velum.app.platform.LocalHaptics
import dev.velum.app.ui.motion.Motion
import dev.velum.app.ui.motion.pressableScale
import dev.velum.app.ui.theme.VelumBlack
import dev.velum.app.ui.theme.VelumDivider
import dev.velum.app.ui.theme.VelumInkSecondary
import dev.velum.app.ui.theme.VelumOnBlack
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
    val strings = LocalStrings.current
    val haptics = LocalHaptics.current
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
            TabIcon(
                selected = selected == ShellTab.Files && !fabOpen,
                filled = Icons.Filled.Folder,
                outlined = Icons.Outlined.Folder,
                contentDescription = strings.allFiles,
                onClick = {
                    haptics.perform(HapticKind.Selection)
                    onFiles()
                },
            )
            SpacerFab()
            TabIcon(
                selected = selected == ShellTab.Explore && !fabOpen,
                filled = Icons.Filled.Apps,
                outlined = Icons.Outlined.Apps,
                contentDescription = strings.explore,
                onClick = {
                    haptics.perform(HapticKind.Selection)
                    onExplore()
                },
            )
        }
        val fabScale by animateFloatAsState(
            targetValue = if (fabOpen) 0.94f else 1f,
            animationSpec = Motion.tweenOut(Motion.PressMs),
            label = "fabScale",
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = (-18).dp)
                .size(58.dp)
                .graphicsLayer {
                    scaleX = fabScale
                    scaleY = fabScale
                }
                .clip(CircleShape)
                .background(VelumBlack)
                .border(3.dp, VelumSurface, CircleShape)
                .pressableScale(
                    haptic = true,
                    role = Role.Button,
                    onClick = onFab,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (fabOpen) Icons.Outlined.Close else Icons.Outlined.AutoAwesome,
                contentDescription = if (fabOpen) strings.close else strings.create,
                tint = VelumOnBlack,
                modifier = Modifier.size(26.dp),
            )
        }
    }
}

@Composable
private fun TabIcon(
    selected: Boolean,
    filled: androidx.compose.ui.graphics.vector.ImageVector,
    outlined: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    val tint by animateColorAsState(
        targetValue = if (selected) VelumBlack else VelumInkSecondary,
        animationSpec = Motion.tweenOut(Motion.ToggleMs),
        label = "tabTint",
    )
    val scale by animateFloatAsState(
        targetValue = if (selected) 1.06f else 1f,
        animationSpec = Motion.tweenOut(Motion.ToggleMs),
        label = "tabScale",
    )
    // Match HeaderIcon: CD + plain clickable on the Box (Icon CD null).
    // pressableScale's trailing graphicsLayer split the a11y node under R8 so
    // UiAutomator saw clickable=false on the CD (#106 minifiedTest).
    Box(
        Modifier
            .defaultMinSize(48.dp, 48.dp)
            .clickable(role = Role.Tab, onClick = onClick)
            .clearAndSetSemantics {
                this.contentDescription = contentDescription
                role = Role.Tab
                onClick {
                    onClick()
                    true
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (selected) filled else outlined,
            contentDescription = null,
            tint = tint,
            modifier = Modifier
                .size(26.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                },
        )
    }
}

@Composable
private fun SpacerFab() {
    Box(Modifier.size(58.dp)) {}
}
