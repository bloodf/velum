package dev.velum.ai.pipeline

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/** The locale an in-app language preference stands for, or null for "system" (the device's own). */
fun localeForAppLanguage(appLanguage: String?): Locale? = when (appLanguage) {
    "en" -> Locale.forLanguageTag("en")
    "pt-BR" -> Locale.forLanguageTag("pt-BR")
    else -> null
}

/**
 * A context whose resources follow the in-app language, not only the device's. Notifications, channel names and
 * mic errors read Android resources through this, so they match the rest of the UI.
 */
fun Context.inAppLanguage(): Context {
    val locale = localeForAppLanguage(AiRuntimeHolder.settings?.preferences?.value?.appLanguage) ?: return this
    val config = Configuration(resources.configuration).apply { setLocale(locale) }
    return createConfigurationContext(config)
}
