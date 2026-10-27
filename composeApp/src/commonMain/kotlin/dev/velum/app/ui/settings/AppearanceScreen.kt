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

    Column(
        Modifier
            .fillMaxSize()
            .background(VelumSurface)
            .verticalScroll(rememberScrollState()),
    ) {
        ScreenTopBar(strings.settingsAppearance, onBack = onBack)
        SectionLabel(strings.settingsTheme)
        ThemeOption(strings.themeSystem, prefs.themeMode == "system") {
            scope.launch { container.settingsRepository.setThemeMode("system") }
        }
        ThemeOption(strings.themeLight, prefs.themeMode == "light") {
            scope.launch { container.settingsRepository.setThemeMode("light") }
        }
        ThemeOption(strings.themeDark, prefs.themeMode == "dark") {
            scope.launch { container.settingsRepository.setThemeMode("dark") }
        }
        Spacer(Modifier.height(12.dp))
        SectionLabel(strings.settingsLanguage)
        ThemeOption(strings.languageSystem, prefs.appLanguage == "system") {
            scope.launch { container.settingsRepository.setAppLanguage("system") }
        }
        ThemeOption("English", prefs.appLanguage == "en") {
            scope.launch { container.settingsRepository.setAppLanguage("en") }
        }
        ThemeOption("Português (Brasil)", prefs.appLanguage == "pt-BR") {
            scope.launch { container.settingsRepository.setAppLanguage("pt-BR") }
        }
        Spacer(Modifier.height(40.dp))
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
