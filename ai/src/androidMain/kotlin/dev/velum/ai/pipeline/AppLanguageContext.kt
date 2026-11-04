package dev.velum.ai.pipeline

import android.content.Context
import android.content.res.Configuration
import android.os.Looper
import dev.velum.core.settings.SettingsLoadState
import dev.velum.core.settings.SettingsRepository
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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
    // An installed repository is not a loaded one: until load() finished, its preferences are the defaults
    // ("system"), which must not win over the language the user chose.
    val settings = AiRuntimeHolder.settings
    val language = if (settings?.loadState?.value == SettingsLoadState.Ready) {
        settings.preferences.value.appLanguage
    } else {
        readAppLanguageHint(this) ?: settings?.preferences?.value?.appLanguage
    }
    val locale = localeForAppLanguage(language) ?: return this
    val config = Configuration(resources.configuration).apply { setLocale(locale) }
    return createConfigurationContext(config)
}

private const val HINT_PREFS = "velum_app_language_hint"
private const val HINT_KEY = "app_language"

/**
 * Remembers the in-app language outside the encrypted settings, so a process that starts before
 * [AiRuntimeHolder.install] (a WorkManager job resumed in a cold process) can still build its notification in the
 * right language. The language is not a secret. If the process dies right after a language change, the hint can be
 * one change behind until the next start.
 */
fun saveAppLanguageHint(context: Context, appLanguage: String?) {
    context.applicationContext.getSharedPreferences(HINT_PREFS, Context.MODE_PRIVATE)
        .edit().putString(HINT_KEY, appLanguage).apply()
}

/** Null on the main thread: Application.onCreate must not read the disk there (StrictMode in the device walk). */
internal fun readAppLanguageHint(context: Context): String? {
    if (Looper.myLooper() == Looper.getMainLooper()) return null
    return context.applicationContext.getSharedPreferences(HINT_PREFS, Context.MODE_PRIVATE).getString(HINT_KEY, null)
}

/**
 * Saves the in-app language whenever it changes, but only after [settings] finished loading: before that the
 * repository holds the default "system", and saving it would overwrite the user's real choice with a default.
 * Suspends for as long as it is collected.
 */
suspend fun keepAppLanguageHint(context: Context, settings: SettingsRepository) {
    settings.loadState.first { it == SettingsLoadState.Ready }
    settings.preferences.map { it.appLanguage }.distinctUntilChanged().collect { saveAppLanguageHint(context, it) }
}
