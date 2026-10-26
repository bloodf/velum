package dev.velum.app.ui.explore

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.PeopleOutline
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.velum.app.di.AppContainer
import dev.velum.app.i18n.LocalStrings
import dev.velum.app.ui.components.ChevronRow
import dev.velum.app.ui.components.DisplayTitle
import dev.velum.app.ui.theme.VelumBg
import dev.velum.app.ui.theme.VelumBlack

@Composable
fun ExploreScreen(
    container: AppContainer,
    onTemplates: () -> Unit,
    onAutoFlow: () -> Unit,
    onIntegrations: () -> Unit,
    onAccount: () -> Unit,
) {
    val strings = LocalStrings.current
    val settings by container.settingsRepository.settings.collectAsState()
    val modelLabel = settings.chatModel ?: settings.transcriptionModel ?: strings.automatic
    val localLabel = if (settings.hasApiKey) {
        "${settings.cachedModels.size} ${strings.modelsCached}"
    } else {
        strings.noApiKeyYet
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(VelumBg)
            .verticalScroll(rememberScrollState()),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(VelumBlack)
                .padding(horizontal = 20.dp, vertical = 18.dp),
        ) {
            Text(
                "${strings.modelStatus}: $modelLabel",
                color = Color.White,
                fontSize = 14.sp,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "${strings.localUsage}: $localLabel",
                color = Color.White.copy(alpha = 0.72f),
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(14.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.White)
                    .clickable(onClick = onAccount),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (settings.hasApiKey) "BYOK · $modelLabel" else strings.aiProvider,
                    color = VelumBlack,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        DisplayTitle(strings.explore)
        Spacer(Modifier.height(12.dp))

        ChevronRow(strings.privateCloudSync, {}, icon = Icons.Outlined.Cloud, value = strings.enabled)
        ChevronRow(strings.velumDesktop, {}, icon = Icons.Outlined.DesktopWindows, value = "Mac / Windows / Web")

        Spacer(Modifier.height(8.dp))
        ChevronRow(strings.templatesCommunity, onTemplates, icon = Icons.Outlined.LibraryBooks)
        ChevronRow(strings.autoFlow, onAutoFlow, icon = Icons.Outlined.AccountTree)
        ChevronRow(strings.integrations, onIntegrations, icon = Icons.Outlined.Extension)

        Spacer(Modifier.height(8.dp))
        ChevronRow(strings.shareIdeas, {}, icon = Icons.Outlined.Lightbulb)
        ChevronRow(strings.inviteFriend, {}, icon = Icons.Outlined.PeopleOutline)

        Spacer(Modifier.height(8.dp))
        ChevronRow(strings.privacySecurity, {}, icon = Icons.Outlined.Security)

        Spacer(Modifier.height(8.dp))
        ChevronRow(strings.helpSupport, {}, icon = Icons.AutoMirrored.Outlined.HelpOutline)
        ChevronRow(strings.contactSupport, {}, icon = Icons.Outlined.MailOutline)
        ChevronRow(strings.aboutVelum, {}, icon = Icons.Outlined.Info, showDivider = false)

        Spacer(Modifier.height(96.dp))
    }
}
