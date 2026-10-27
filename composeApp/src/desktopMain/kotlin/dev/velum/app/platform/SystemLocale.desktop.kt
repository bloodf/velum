package dev.velum.app.platform

import java.util.Locale

actual fun systemLanguageTag(): String = Locale.getDefault().toLanguageTag()
