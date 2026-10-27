package dev.velum.app.platform

import platform.Foundation.NSLocale
import platform.Foundation.currentLocale
import platform.Foundation.localeIdentifier

actual fun systemLanguageTag(): String =
    NSLocale.currentLocale.localeIdentifier.replace('_', '-')
