package dev.velum.app

import androidx.compose.ui.window.ComposeUIViewController
import dev.velum.app.di.AppContainer
import dev.velum.core.data.DriverFactory
import dev.velum.core.security.IosSecureStore
import platform.UIKit.UIViewController

fun MainViewController(): UIViewController {
    val container = AppContainer(
        secureStore = IosSecureStore(),
        driverFactory = DriverFactory(),
    )
    return ComposeUIViewController { App(container) }
}
