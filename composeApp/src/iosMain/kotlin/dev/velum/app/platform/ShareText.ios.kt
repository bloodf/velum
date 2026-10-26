package dev.velum.app.platform

actual fun shareText(title: String, text: String) {
    // iOS host deferred; no-op until UIKit share sheet is wired.
}
