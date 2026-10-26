package dev.velum.app.ui.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.velum.app.i18n.LocalStrings
import dev.velum.app.ui.motion.Motion
import dev.velum.app.ui.motion.pressableScale

@Composable
fun FabOverlay(
    visible: Boolean,
    onStartRecording: () -> Unit,
    onImportAudio: () -> Unit,
    onDismiss: () -> Unit,
    isCapturing: Boolean = false,
) {
    val strings = LocalStrings.current
    val onScrim = Color.White
    val reduce = Motion.reduceMotion()
    AnimatedVisibility(
        visible = visible,
        enter = if (reduce) {
            fadeIn(Motion.tweenOut(0))
        } else {
            fadeIn(Motion.tweenOut(Motion.OverlayMs)) +
                scaleIn(
                    animationSpec = Motion.tweenOut(Motion.OverlayMs),
                    initialScale = Motion.EnterScaleFrom,
                    transformOrigin = TransformOrigin(0.5f, 1f),
                )
        },
        exit = if (reduce) {
            fadeOut(Motion.tweenOut(0))
        } else {
            fadeOut(Motion.tweenOut(160)) +
                scaleOut(
                    animationSpec = Motion.tweenOut(160),
                    targetScale = Motion.EnterScaleFrom,
                    transformOrigin = TransformOrigin(0.5f, 1f),
                )
        },
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.42f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        ) {
            Spacer(Modifier.weight(1f))
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp)
                    .padding(bottom = 96.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
            ) {
                FabAction(
                    Icons.Outlined.Mic,
                    if (isCapturing) strings.stopRecording else strings.startRecording,
                    onScrim,
                    onStartRecording,
                )
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = onScrim.copy(alpha = 0.28f), thickness = 0.5.dp)
                Spacer(Modifier.height(8.dp))
                FabAction(Icons.Outlined.FileDownload, strings.importAudio, onScrim, onImportAudio)
            }
        }
    }
}

@Composable
private fun FabAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .pressableScale(haptic = true, role = Role.Button, onClick = onClick)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Text(label, color = tint, fontWeight = FontWeight.Medium, fontSize = 17.sp)
    }
}
