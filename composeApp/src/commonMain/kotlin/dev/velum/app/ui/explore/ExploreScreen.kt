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
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
    var infoTitle by remember { mutableStateOf<String?>(null) }
    var infoBody by remember { mutableStateOf("") }

    if (infoTitle != null) {
        AlertDialog(
            onDismissRequest = { infoTitle = null },
            title = { Text(infoTitle.orEmpty()) },
            text = { Text(infoBody) },
            confirmButton = {
                TextButton(onClick = { infoTitle = null }) { Text(strings.close) }
            },
        )
    }

    fun showInfo(title: String, body: String) {
        infoTitle = title
        infoBody = body
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

        ChevronRow(strings.templatesCommunity, onTemplates, icon = Icons.AutoMirrored.Outlined.LibraryBooks)
        ChevronRow(strings.autoFlow, onAutoFlow, icon = Icons.Outlined.AccountTree)
        ChevronRow(strings.integrations, onIntegrations, icon = Icons.Outlined.Extension)

        Spacer(Modifier.height(8.dp))
        ChevronRow(
            strings.privacySecurity,
            {
                showInfo(
                    strings.privacySecurity,
                    "Velum é local-first: sem telemetria Plaud/Datadog/PostHog. " +
                        "Chaves e BYO API ficam no armazenamento seguro do aparelho.",
                )
            },
            icon = Icons.Outlined.Security,
        )
        ChevronRow(
            strings.helpSupport,
            {
                showInfo(
                    strings.helpSupport,
                    "Use Configurações → Conta para BYO API, gerenciador de modelos e importação de chaves do Note Pro.",
                )
            },
            icon = Icons.AutoMirrored.Outlined.HelpOutline,
        )
        ChevronRow(
            strings.aboutVelum,
            {
                showInfo(
                    strings.aboutVelum,
                    "Velum 0.1.0-foundation\nProcessamento local + API OpenAI-compatível (BYO).\nSem servidores Plaud.",
                )
            },
            icon = Icons.Outlined.Info,
            showDivider = false,
        )

        Spacer(Modifier.height(96.dp))
    }
}
