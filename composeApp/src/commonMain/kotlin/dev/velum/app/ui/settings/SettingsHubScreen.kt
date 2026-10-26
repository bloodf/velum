package dev.velum.app.ui.settings

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.velum.app.di.AppContainer
import dev.velum.app.i18n.LocalStrings
import dev.velum.app.ui.components.ChevronRow
import dev.velum.app.ui.components.ScreenTopBar
import dev.velum.app.ui.theme.VelumBg
import dev.velum.app.ui.theme.VelumInk
import dev.velum.app.ui.theme.VelumInkSecondary
import dev.velum.app.ui.theme.VelumSurface

@Composable
fun SettingsHubScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onPersonalization: () -> Unit,
    onPreferences: () -> Unit,
    onAccount: () -> Unit,
) {
    val strings = LocalStrings.current
    val count by container.repository.observeRecordings().collectAsState(emptyList())
    val totalMins = count.sumOf { it.duration_seconds } / 60.0 / 60.0

    Column(
        Modifier
            .fillMaxSize()
            .background(VelumSurface)
            .verticalScroll(rememberScrollState()),
    ) {
        ScreenTopBar(strings.settings, onBack = onBack)
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .size(88.dp)
                .align(Alignment.CenterHorizontally)
                .clip(CircleShape)
                .background(VelumBg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Person, null, tint = VelumInk, modifier = Modifier.size(40.dp))
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "Velum",
            fontWeight = FontWeight.SemiBold,
            fontSize = 20.sp,
            color = VelumInk,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Spacer(Modifier.height(20.dp))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            StatCell(strings.daysOfUse, "1")
            StatCell(strings.recordings, "${count.size}")
            StatCell(strings.totalTime, "${"%.1f".format(totalMins)}h")
        }
        Spacer(Modifier.height(16.dp))
        Heatmap()
        Spacer(Modifier.height(24.dp))
        ChevronRow(
            strings.currentWorkspace,
            {},
            icon = Icons.Outlined.Home,
            value = strings.personalWorkspace,
        )
        ChevronRow(strings.personalization, onPersonalization, icon = Icons.Outlined.ViewAgenda)
        ChevronRow(strings.preferences, onPreferences, icon = Icons.Outlined.Tune)
        ChevronRow(strings.account, onAccount, icon = Icons.Outlined.Person, showDivider = false)
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun StatCell(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = VelumInkSecondary, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        Text(value, color = VelumInk, fontWeight = FontWeight.Thin, fontSize = 28.sp)
    }
}

@Composable
private fun Heatmap() {
    Column(Modifier.padding(horizontal = 28.dp)) {
        repeat(4) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(16) { col ->
                    val active = row == 1 && col == 14
                    Box(
                        Modifier
                            .size(14.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(
                                if (active) androidx.compose.ui.graphics.Color(0xFF64D2FF)
                                else androidx.compose.ui.graphics.Color(0xFFECECF0),
                            ),
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}
