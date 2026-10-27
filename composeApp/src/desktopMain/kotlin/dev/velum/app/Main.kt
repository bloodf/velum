package dev.velum.app

import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.velum.app.di.AppContainer
import dev.velum.app.i18n.En
import dev.velum.core.data.DriverFactory
import dev.velum.core.integrations.IntegrationConfig
import dev.velum.core.security.DesktopSecureStore

fun main() = application {
    val container = AppContainer(
        secureStore = DesktopSecureStore(),
        driverFactory = DriverFactory(),
        integrationConfig = IntegrationConfig.fromEnv { key -> System.getenv(key) },
    )
    Window(
        onCloseRequest = ::exitApplication,
        title = "Velum",
        // Wide default so the adaptive list–detail library layout engages.
        state = rememberWindowState(width = 1440.dp, height = 900.dp),
        icon = painterResource("icons/velum-256.png"),
    ) {
        App(container, strings = En)
    }
}
