package dev.velum.app.platform

actual fun openUrl(url: String) {
    // iOS shell is not wired for UIApplication.open yet.
}
