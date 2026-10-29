package dev.velum.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.velum.app.i18n.LocalStrings
import dev.velum.app.platform.openUrl
import dev.velum.app.ui.brand.VelumLogo
import dev.velum.app.ui.components.ChevronRow
import dev.velum.app.ui.components.ScreenTopBar
import dev.velum.app.ui.theme.VelumInk
import dev.velum.app.ui.theme.VelumInkSecondary
import dev.velum.app.ui.theme.VelumSurface

private const val GITHUB_REPO = "https://github.com/bloodf/velum"
private const val GITHUB_ISSUES = "https://github.com/bloodf/velum/issues"
private const val PRIVACY_POLICY = "https://github.com/bloodf/velum/blob/main/docs/PRIVACY.md"
private const val APP_VERSION = "0.1.0-foundation"

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val strings = LocalStrings.current
    // Pin ScreenTopBar outside the scroll so Back stays reachable (#118).
    Column(
        Modifier
            .fillMaxSize()
            .background(VelumSurface),
    ) {
        ScreenTopBar(strings.aboutVelum, onBack = onBack)
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(12.dp))
            VelumLogo(size = 72.dp, modifier = Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(12.dp))
            Text(
                "Velum",
                fontWeight = FontWeight.SemiBold,
                fontSize = 22.sp,
                color = VelumInk,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Text(
                APP_VERSION,
                color = VelumInkSecondary,
                fontSize = 14.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            Text(
                strings.settingsUi.aboutBody,
                color = VelumInkSecondary,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            Spacer(Modifier.height(16.dp))
            ChevronRow(strings.settingsUi.openSourceLicenses, {
                openUrl("$GITHUB_REPO/blob/main/THIRD_PARTY.md")
            })
            ChevronRow(strings.settingsUi.privacyPolicy, { openUrl(PRIVACY_POLICY) })
            ChevronRow(strings.settingsUi.githubRepo, { openUrl(GITHUB_REPO) })
            ChevronRow(strings.settingsUi.reportIssue, { openUrl(GITHUB_ISSUES) })
            ChevronRow(
                strings.settingsUi.contributeTemplates,
                { openUrl("$GITHUB_REPO/blob/main/CONTRIBUTING.md") },
                showDivider = false,
            )
            Spacer(Modifier.height(40.dp))
        }
    }
}
