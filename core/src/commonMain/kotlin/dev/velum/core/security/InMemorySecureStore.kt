package dev.velum.core.security

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** In-memory store for tests and desktop prototype until OS keyring is wired. */
class InMemorySecureStore : SecureStore {
    private val mutex = Mutex()
    private val map = mutableMapOf<String, String>()

    override suspend fun putString(key: String, value: String) {
        mutex.withLock { map[key] = value }
    }

    override suspend fun getString(key: String): String? = mutex.withLock { map[key] }

    override suspend fun remove(key: String) {
        mutex.withLock { map.remove(key) }
    }

    override suspend fun contains(key: String): Boolean = mutex.withLock { map.containsKey(key) }
}
