package dev.velum.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.velum.app.i18n.LocalStrings
import dev.velum.app.ui.motion.Motion
import dev.velum.app.ui.motion.pressableScale
import dev.velum.app.ui.theme.VelumBlack
import dev.velum.app.ui.theme.VelumDivider
import dev.velum.app.ui.theme.VelumInk
import dev.velum.app.ui.theme.VelumInkSecondary
import dev.velum.app.ui.theme.VelumSurface

@Composable
fun ScreenTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val strings = LocalStrings.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            onBack != null -> {
                Box(
                    Modifier
                        .defaultMinSize(48.dp, 48.dp)
                        .clickable(onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = strings.ux.back,
                        tint = VelumInk,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
            onClose != null -> {
                Box(
                    Modifier
                        .defaultMinSize(48.dp, 48.dp)
                        .clickable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = strings.close,
                        tint = VelumInk,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
            else -> Spacer(Modifier.width(48.dp))
        }
        Text(
            title,
            color = VelumInk,
            fontWeight = FontWeight.SemiBold,
            fontSize = 17.sp,
            modifier = Modifier.weight(1f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Box(modifier = Modifier.width(48.dp), contentAlignment = Alignment.CenterEnd) {
            trailing?.invoke()
        }
    }
}

@Composable
fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        color = VelumInkSecondary,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
        letterSpacing = 0.6.sp,
    )
}

@Composable
fun SuggestionChip(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .border(1.dp, VelumDivider, RoundedCornerShape(20.dp))
            .clip(RoundedCornerShape(20.dp))
            .pressableScale(haptic = false, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(text, color = VelumInk, fontSize = 13.sp)
    }
}

@Composable
fun OutlineActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    destructive: Boolean = false,
) {
    val border = if (destructive) Color(0xFFFF6B5A) else VelumDivider
    val tint = if (destructive) Color(0xFFFF6B5A) else VelumInk
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, border, RoundedCornerShape(10.dp))
            .background(VelumSurface)
            .pressableScale(haptic = true, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = tint, fontWeight = FontWeight.Medium, fontSize = 16.sp)
    }
}

@Composable
fun DualActionRow(
    left: String,
    right: String,
    onLeft: () -> Unit,
    onRight: () -> Unit,
    rightEnabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlineButton(left, onLeft, modifier = Modifier.weight(1f))
        BlackButton(right, onRight, modifier = Modifier.weight(1f), enabled = rightEnabled)
    }
}

@Composable
fun MetaRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = VelumInkSecondary, fontSize = 14.sp)
        Text(value, color = VelumInk, fontSize = 14.sp)
    }
}

@Composable
fun EmptyState(
    title: String,
    subtitle: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val reduce = Motion.reduceMotion()
    val progress = remember { Animatable(if (reduce) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!reduce) {
            progress.animateTo(1f, tween(Motion.OverlayMs, easing = Motion.EaseOut))
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = progress.value
                val s = Motion.EnterScaleFrom + (1f - Motion.EnterScaleFrom) * progress.value
                scaleX = s
                scaleY = s
            }
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, color = VelumInk, fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
        Spacer(Modifier.height(8.dp))
        Text(
            subtitle,
            color = VelumInkSecondary,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(20.dp))
            OutlineActionButton(actionLabel, onAction)
        }
    }
}

@Composable
fun SmallEditPill(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(VelumBlack)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(text, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}
