package dev.velum.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.velum.app.di.AppContainer
import dev.velum.app.i18n.LocalStrings
import dev.velum.app.ui.components.ScreenTopBar
import dev.velum.app.ui.components.SectionLabel
import dev.velum.app.ui.theme.VelumInk
import dev.velum.app.ui.theme.VelumInkSecondary
import dev.velum.app.ui.theme.VelumSurface
import kotlinx.coroutines.launch

@Composable
fun AppearanceScreen(
    container: AppContainer,
    onBack: () -> Unit,
) {
    val strings = LocalStrings.current
    val prefs by container.settingsRepository.preferences.collectAsState()
    val scope = rememberCoroutineScope()

    // Pin ScreenTopBar outside the scroll so Back stays reachable after
    // performScrollTo on language/theme rows (device-walk #106 root cause).
    Column(
        Modifier
            .fillMaxSize()
            .background(VelumSurface),
    ) {
        ScreenTopBar(strings.settingsUi.appearance, onBack = onBack)
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            SectionLabel(strings.settingsUi.theme)
            ThemeOption(strings.settingsUi.themeSystem, prefs.themeMode == "system") {
                scope.launch { runCatching { container.settingsRepository.setThemeMode("system") } }
            }
            ThemeOption(strings.settingsUi.themeLight, prefs.themeMode == "light") {
                scope.launch { runCatching { container.settingsRepository.setThemeMode("light") } }
            }
            ThemeOption(strings.settingsUi.themeDark, prefs.themeMode == "dark") {
                scope.launch { runCatching { container.settingsRepository.setThemeMode("dark") } }
            }
            Spacer(Modifier.height(12.dp))
            SectionLabel(strings.settingsUi.language)
            ThemeOption(strings.settingsUi.languageSystem, prefs.appLanguage == "system") {
                scope.launch { runCatching { container.settingsRepository.setAppLanguage("system") } }
            }
            ThemeOption(strings.languageEn, prefs.appLanguage == "en") {
                scope.launch { runCatching { container.settingsRepository.setAppLanguage("en") } }
            }
            ThemeOption(strings.languagePtBr, prefs.appLanguage == "pt-BR") {
                scope.launch { runCatching { container.settingsRepository.setAppLanguage("pt-BR") } }
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun ThemeOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Text(label, color = VelumInk, fontSize = 16.sp)
        if (selected) {
            Text("✓", color = VelumInkSecondary, fontSize = 13.sp)
        }
    }
}
