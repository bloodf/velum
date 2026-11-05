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
    val appLanguage = AiRuntimeHolder.settings?.preferences?.value?.appLanguage ?: BootLanguage.current
    val locale = localeForAppLanguage(appLanguage) ?: return this
    val config = Configuration(resources.configuration).apply { setLocale(locale) }
    return createConfigurationContext(config)
}

/**
 * A plain copy of the in-app language, the only key in `velum_boot`, so a cold start can use it before the settings
 * vault is open (#348). It is not a secret: the UI shows it. Call [load], [save] and [clear] off the main thread.
 *
 * ponytail: if the language changes and the process dies before [save], the next cold start uses the previous
 * language until settings load and re-save it; write it from setAppLanguage if that window matters.
 */
object BootLanguage {
    private const val PREFS = "velum_boot"
    private const val KEY = "app_language"

    /** The copy read at this process start, or null before [load] finishes or when it failed. */
    @Volatile
    var current: String? = null
        private set

    fun load(context: Context): String? {
        current = runCatching { prefs(context).getString(KEY, null) }.getOrNull()
        return current
    }

    fun save(context: Context, appLanguage: String) {
        current = appLanguage
        prefs(context).edit().putString(KEY, appLanguage).commit()
    }

    /** Back to "system", for "Wipe all data". */
    fun clear(context: Context) = save(context, "system")

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
