package dev.velum.app.importing

import kotlin.concurrent.Volatile

/**
 * Original file names of imports, keyed by the cache path they were copied to. The picker records one,
 * the pipeline takes it once. Copy-on-write so no lock is needed; a lost entry only costs the readable
 * title (the cache name is the fallback).
 */
object ImportDisplayNames {
    @Volatile private var names: Map<String, String> = emptyMap()

    fun put(cachedPath: String, displayName: String?) {
        if (displayName.isNullOrBlank()) return
        names = names + (cachedPath to displayName)
    }

    fun take(cachedPath: String): String? {
        val name = names[cachedPath] ?: return null
        names = names - cachedPath
        return name
    }
}
