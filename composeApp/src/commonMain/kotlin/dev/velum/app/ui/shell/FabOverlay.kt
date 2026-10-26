package dev.velum.app.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.velum.app.i18n.LocalStrings
import dev.velum.app.ui.theme.VelumDivider
import dev.velum.app.ui.theme.VelumInk

@Composable
fun FabOverlay(
    onStartRecording: () -> Unit,
    onImportAudio: () -> Unit,
    onDismiss: () -> Unit,
    isCapturing: Boolean = false,
) {
    val strings = LocalStrings.current
    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFFF9F9F9).copy(alpha = 0.96f))
            .clickable(onClick = onDismiss),
    ) {
        Spacer(Modifier.weight(1f))
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp)
                .padding(bottom = 96.dp)
                .clickable(enabled = false) {},
        ) {
            FabAction(
                Icons.Outlined.Mic,
                if (isCapturing) strings.stopRecording else strings.startRecording,
                onStartRecording,
            )
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = VelumDivider, thickness = 0.5.dp)
            Spacer(Modifier.height(8.dp))
            FabAction(Icons.Outlined.FileDownload, strings.importAudio, onImportAudio)
        }
    }
}

@Composable
private fun FabAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = VelumInk, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Text(label, color = VelumInk, fontWeight = FontWeight.Medium, fontSize = 17.sp)
    }
}
