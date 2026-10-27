package dev.velum.app.platform

import java.awt.Desktop
import java.net.URI

actual fun openUrl(url: String) {
    runCatching {
        if (Desktop.isDesktopSupported()) {
            Desktop.getDesktop().browse(URI(url))
        }
    }
}
