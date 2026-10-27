package dev.velum.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.velum.app.ui.motion.pressableScale
import dev.velum.app.ui.theme.VelumAiCyan
import dev.velum.app.ui.theme.VelumAiPurple
import dev.velum.app.ui.theme.VelumBlack
import dev.velum.app.ui.theme.VelumDisabledFill
import dev.velum.app.ui.theme.VelumDivider
import dev.velum.app.ui.theme.VelumInk
import dev.velum.app.ui.theme.VelumInkSecondary
import dev.velum.app.ui.theme.VelumOnBlack
import dev.velum.app.ui.theme.VelumOnDisabled
import dev.velum.app.ui.theme.VelumSurface

@Composable
fun BlackButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val fill = if (enabled) VelumBlack else VelumDisabledFill
    val label = if (enabled) VelumOnBlack else VelumOnDisabled
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(fill)
            .pressableScale(enabled = enabled, haptic = true, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = label, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    }
}

@Composable
fun OutlineButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: ImageVector? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, VelumDivider, RoundedCornerShape(12.dp))
            .background(VelumSurface)
            .pressableScale(enabled = enabled, haptic = true, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (leading != null) {
            Icon(
                leading,
                contentDescription = null,
                tint = if (enabled) VelumInk else VelumInkSecondary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text,
            color = if (enabled) VelumInk else VelumInkSecondary,
            fontWeight = FontWeight.Medium,
            fontSize = 15.sp,
        )
    }
}

@Composable
fun ChevronRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    value: String? = null,
    valueColor: Color = VelumInkSecondary,
    titleColor: Color = VelumInk,
    showDivider: Boolean = true,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .pressableScale(haptic = false, role = Role.Button, onClick = onClick)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, null, tint = VelumInk, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(14.dp))
            }
            Text(title, color = titleColor, fontSize = 16.sp, modifier = Modifier.weight(1f))
            if (value != null) {
                Text(value, color = valueColor, fontSize = 15.sp)
                Spacer(Modifier.width(6.dp))
            }
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                null,
                tint = VelumInkSecondary,
                modifier = Modifier.size(20.dp),
            )
        }
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = if (icon != null) 56.dp else 20.dp),
                thickness = 0.5.dp,
                color = VelumDivider,
            )
        }
    }
}

@Composable
fun ToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    description: String? = null,
    showDivider: Boolean = true,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(title, color = VelumInk, fontSize = 16.sp)
                if (description != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(description, color = VelumInkSecondary, fontSize = 13.sp, lineHeight = 18.sp)
                }
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = VelumBlack,
                    uncheckedThumbColor = Color.White,
                    uncheckedTrackColor = Color(0xFFE5E5EA),
                    uncheckedBorderColor = Color.Transparent,
                ),
            )
        }
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 20.dp),
                thickness = 0.5.dp,
                color = VelumDivider,
            )
        }
    }
}

@Composable
fun DisplayTitle(
    text: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = TextStyle(
                fontWeight = FontWeight.Thin,
                fontSize = 34.sp,
                lineHeight = 40.sp,
                color = VelumInk,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (trailing != null) trailing()
    }
}

@Composable
fun AiGlowFooter(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(120.dp)
            .background(
                Brush.verticalGradient(
                    listOf(Color.Transparent, VelumAiPurple.copy(alpha = 0.18f), VelumAiCyan.copy(alpha = 0.28f)),
                ),
            ),
    )
}

@Composable
fun ThinDivider(startIndent: Int = 20) {
    HorizontalDivider(
        modifier = Modifier.padding(start = startIndent.dp),
        thickness = 0.5.dp,
        color = VelumDivider,
    )
}
