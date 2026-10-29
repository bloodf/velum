package dev.velum.app.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.velum.ai.local.DefaultLocalCatalog
import dev.velum.ai.local.detectDeviceComputeClass
import dev.velum.app.i18n.LocalStrings
import dev.velum.app.i18n.ModelCatalogUi
import dev.velum.app.ui.brand.VelumHero
import dev.velum.app.ui.components.BlackButton
import dev.velum.app.ui.components.OutlineButton
import dev.velum.app.ui.theme.VelumBg
import dev.velum.app.ui.theme.VelumInk
import dev.velum.app.ui.theme.VelumInkSecondary
import dev.velum.app.ui.theme.VelumSurface

/**
 * Phone-first onboarding: Velum works with no Plaud at all.
 * Paths: phone mic · import from any recorder · connect Plaud · recommended models / API.
 *
 * Skip is a real layout sibling under the scroll (not a Box overlay) so it stays
 * on-screen and receives pointer/a11y clicks without competing with the scroll
 * hit target (#106 / #115).
 */
@Composable
fun FirstRunScreen(
    onPhoneMic: () -> Unit,
    onImportRecorder: () -> Unit,
    onConnectPlaud: () -> Unit,
    onDownloadModels: () -> Unit,
    onDownloadRecommended: () -> Unit = onDownloadModels,
    onUseApi: () -> Unit,
    onSkip: () -> Unit,
    languageHint: String? = null,
) {
    val strings = LocalStrings.current
    val fr = strings.firstRun
    val deviceClass = remember { detectDeviceComputeClass() }
    val lang = languageHint ?: strings.localeTag
    val recommendedIds = remember(lang, deviceClass) {
        ModelCatalogUi.recommendedModelIds(lang, deviceClass)
    }
    val asrName = remember(lang, deviceClass) {
        ModelCatalogUi.recommendedAsrDisplayName(lang, deviceClass)
    }
    val asrCopy = remember(lang, asrName) {
        val id = recommendedIds.firstOrNull()
            ?: return@remember ModelCatalogUi.copyFor(strings.localeTag, "parakeet-tdt-0.6b-v3-int8")
        ModelCatalogUi.copyFor(strings.localeTag, id)
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(VelumBg),
    ) {
        // Cap hero so brand + primary CTAs share the first viewport with Skip.
        val heroMax = maxHeight * 0.36f
        Column(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                VelumHero(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = heroMax)
                        .clip(RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp)),
                )
                Column(Modifier.padding(horizontal = 24.dp, vertical = 20.dp)) {
                    Text("Velum", fontWeight = FontWeight.Bold, fontSize = 32.sp, color = VelumInk)
                    Spacer(Modifier.height(10.dp))
                    Text(fr.title, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, color = VelumInk)
                    Spacer(Modifier.height(8.dp))
                    Text(fr.body, color = VelumInkSecondary, fontSize = 15.sp, lineHeight = 22.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(fr.phoneFirstHint, color = VelumInkSecondary, fontSize = 14.sp, lineHeight = 20.sp)
                    Spacer(Modifier.height(20.dp))

                    Text(fr.howToStart, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = VelumInk)
                    Spacer(Modifier.height(12.dp))
                    BlackButton(
                        fr.usePhoneMic,
                        onPhoneMic,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlineButton(
                        fr.importFromRecorder,
                        onImportRecorder,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlineButton(
                        fr.connectPlaud,
                        onConnectPlaud,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Spacer(Modifier.height(28.dp))
                    Text(fr.modelsHeading, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = VelumInk)
                    Spacer(Modifier.height(8.dp))
                    Text(fr.localVsApi, color = VelumInkSecondary, fontSize = 14.sp, lineHeight = 20.sp)
                    Spacer(Modifier.height(14.dp))
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(VelumSurface)
                            .padding(16.dp),
                    ) {
                        Text(fr.recommendedModelLabel, color = VelumInkSecondary, fontSize = 12.sp)
                        Spacer(Modifier.height(4.dp))
                        Text(asrName, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = VelumInk)
                        Spacer(Modifier.height(6.dp))
                        Text(asrCopy.blurb, color = VelumInk, fontSize = 14.sp, lineHeight = 20.sp)
                        Spacer(Modifier.height(8.dp))
                        Text(fr.recommendedPackHint, color = VelumInkSecondary, fontSize = 13.sp, lineHeight = 18.sp)
                        Spacer(Modifier.height(6.dp))
                        recommendedIds.drop(1).forEach { id ->
                            val name = DefaultLocalCatalog.firstOrNull { it.id == id }?.displayName ?: id
                            Text("• $name", color = VelumInkSecondary, fontSize = 13.sp)
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    BlackButton(
                        fr.downloadRecommended,
                        onDownloadRecommended,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlineButton(
                        fr.download,
                        onDownloadModels,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlineButton(
                        fr.useApi,
                        onUseApi,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(24.dp))
                }
            }

            // Pinned sibling — must stay on-screen without scrolling (#106 product).
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(VelumBg)
                    .padding(horizontal = 24.dp, vertical = 12.dp),
            ) {
                OutlineButton(
                    fr.skip,
                    onSkip,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    fr.skipHint,
                    color = VelumInkSecondary,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )
            }
        }
    }
}
