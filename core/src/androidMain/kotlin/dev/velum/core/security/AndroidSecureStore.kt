package dev.velum.core.security

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.ConcurrentHashMap

/**
 * Private SharedPreferences-backed store for the foundation build.
 * M1 should migrate to Android Keystore + AES-GCM (Tink).
 */
class AndroidSecureStore(
    context: Context,
) : SecureStore {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("velum_secure", Context.MODE_PRIVATE)
    private val cache = ConcurrentHashMap<String, String>()

    override suspend fun putString(key: String, value: String) {
        cache[key] = value
        prefs.edit().putString(key, value).apply()
    }

    override suspend fun getString(key: String): String? =
        cache[key] ?: prefs.getString(key, null)?.also { cache[key] = it }

    override suspend fun remove(key: String) {
        cache.remove(key)
        prefs.edit().remove(key).apply()
    }

    override suspend fun contains(key: String): Boolean =
        cache.containsKey(key) || prefs.contains(key)
}
