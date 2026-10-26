package dev.velum.app.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.velum.app.i18n.LocalStrings
import dev.velum.app.ui.brand.VelumHero
import dev.velum.app.ui.components.BlackButton
import dev.velum.app.ui.components.OutlineButton
import dev.velum.app.ui.theme.VelumBg
import dev.velum.app.ui.theme.VelumInk
import dev.velum.app.ui.theme.VelumInkSecondary

@Composable
fun FirstRunScreen(
    onDownload: () -> Unit,
    onUseApi: () -> Unit,
    onSkip: () -> Unit,
) {
    val strings = LocalStrings.current
    Column(
        Modifier
            .fillMaxSize()
            .background(VelumBg)
            .verticalScroll(rememberScrollState()),
    ) {
        VelumHero(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp)),
        )
        Column(Modifier.padding(horizontal = 24.dp, vertical = 28.dp)) {
            Text("Velum", fontWeight = FontWeight.Bold, fontSize = 32.sp, color = VelumInk)
            Spacer(Modifier.height(12.dp))
            Text(strings.firstRun.title, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, color = VelumInk)
            Spacer(Modifier.height(10.dp))
            Text(strings.firstRun.body, color = VelumInkSecondary, fontSize = 15.sp, lineHeight = 22.sp)
            Spacer(Modifier.height(14.dp))
            Text(strings.firstRun.localVsApi, color = VelumInkSecondary, fontSize = 14.sp, lineHeight = 20.sp)
            Spacer(Modifier.height(24.dp))
            Text(strings.firstRun.modelsHeading, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = VelumInk)
            Spacer(Modifier.height(10.dp))
            ModelLine(strings.firstRun.modelAsr)
            ModelLine(strings.firstRun.modelDiarization)
            ModelLine(strings.firstRun.modelSummary)
            Spacer(Modifier.height(28.dp))
            BlackButton(strings.firstRun.download, onDownload, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            OutlineButton(strings.firstRun.useApi, onUseApi, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            OutlineButton(strings.firstRun.skip, onSkip, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ModelLine(text: String) {
    Text(
        "• $text",
        color = VelumInk,
        fontSize = 15.sp,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}
