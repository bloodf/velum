package dev.velum.app

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.velum.app.di.AppContainer
import dev.velum.core.security.DesktopSecureStore

fun main() = application {
    val container = AppContainer(secureStore = DesktopSecureStore())
    Window(
        onCloseRequest = ::exitApplication,
        title = "Velum",
        state = rememberWindowState(width = 1100.dp, height = 720.dp),
    ) {
        App(container)
    }
}
