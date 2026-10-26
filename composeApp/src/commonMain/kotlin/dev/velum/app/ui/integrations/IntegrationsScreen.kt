package dev.velum.app.ui.integrations

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.velum.app.di.AppContainer
import dev.velum.app.i18n.LocalStrings
import dev.velum.app.ui.components.ScreenTopBar
import dev.velum.app.ui.theme.VelumBg
import dev.velum.app.ui.theme.VelumDivider
import dev.velum.app.ui.theme.VelumInk
import dev.velum.app.ui.theme.VelumInkSecondary
import dev.velum.app.ui.theme.VelumSurface
import dev.velum.core.calendar.CalendarLookupResult
import dev.velum.core.data.epochSecondsNow

@Composable
fun IntegrationsScreen(
    container: AppContainer,
    onBack: () -> Unit,
) {
    val strings = LocalStrings.current
    var statusLabel by remember { mutableStateOf(strings.localCalendarHint) }
    var statusColor by remember { mutableStateOf(VelumInkSecondary) }

    LaunchedEffect(container) {
        when (val result = container.calendarProvider.currentOrNextEvent(epochSecondsNow() * 1000)) {
            is CalendarLookupResult.Ok -> {
                val title = result.currentOrNext?.title
                if (title.isNullOrBlank()) {
                    statusLabel = strings.oauthDisconnected
                    statusColor = VelumInkSecondary
                } else {
                    statusLabel = "${strings.oauthConnected}: $title"
                    statusColor = Color(0xFF15803D)
                }
            }
            CalendarLookupResult.PermissionDenied -> {
                statusLabel = strings.calendarPermissionBlocked
                statusColor = Color(0xFFB45309)
            }
            is CalendarLookupResult.Unavailable -> {
                statusLabel = result.reason
                statusColor = Color(0xFFB45309)
            }
        }
    }

    Column(Modifier.fillMaxSize().background(VelumBg)) {
        ScreenTopBar(strings.integrations, onBack = onBack)
        Spacer(Modifier.height(8.dp))
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(VelumSurface)
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF4285F4).copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("C", color = Color(0xFF4285F4), fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    strings.localCalendar,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(strings.localCalendarHint, color = VelumInkSecondary, fontSize = 13.sp, lineHeight = 18.sp)
            Spacer(Modifier.height(8.dp))
            Text(
                statusLabel,
                color = statusColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        Spacer(Modifier.weight(1f))
        Column(
            Modifier
                .padding(16.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(VelumSurface)
                .padding(16.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("N", "T", "✓", "D", "S", "↓").forEach { label ->
                    Box(
                        Modifier
                            .size(36.dp)
                            .border(1.dp, VelumDivider, RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(label, fontSize = 12.sp, color = VelumInkSecondary)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(strings.moreAppsComing, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Spacer(Modifier.height(4.dp))
            Text(strings.moreIntegrationsHint, color = VelumInkSecondary, fontSize = 13.sp)
        }
        Spacer(Modifier.height(16.dp))
    }
}
