package dev.velum.app.i18n

/** The tag a language preference stands for: "en" and "pt-BR" are explicit, anything else (the default "system") is the device's. */
fun resolveLanguageTag(appLanguage: String, systemTag: String): String = when (appLanguage) {
    "en" -> "en"
    "pt-BR" -> "pt-BR"
    else -> systemTag
}

/** The recording title for [epochSeconds] in the language the app is set to, "system" meaning the device's. */
fun recordingTitle(epochSeconds: Long, appLanguage: String, systemTag: String): String =
    dev.velum.core.time.VelumDateTime.formatFriendlyTitle(epochSeconds, resolveLanguageTag(appLanguage, systemTag))
