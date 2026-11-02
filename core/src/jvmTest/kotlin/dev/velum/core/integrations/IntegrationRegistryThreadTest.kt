package dev.velum.core.integrations

import dev.velum.core.security.InMemorySecureStore
import dev.velum.core.security.SecureStore
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** The configured-checks read SharedPreferences / secure storage, so they must never run on the caller's thread. */
class IntegrationRegistryThreadTest {
    @Test
    fun listed_and_post_actions_run_the_checks_off_the_caller_thread() = runBlocking {
        val callerThread = Thread.currentThread().name
        val threads = mutableListOf<String>()
        val memory = InMemorySecureStore()
        val store = object : SecureStore by memory {
            override suspend fun getString(key: String): String? {
                synchronized(threads) { threads += Thread.currentThread().name }
                return memory.getString(key)
            }
        }
        val registry = IntegrationRegistry(
            webhook = WebhookSettingsStore(store),
            exportFolderConfigured = { synchronized(threads) { threads += Thread.currentThread().name }; true },
        )
        val listed = registry.listed()
        registry.availablePostActions()
        assertTrue(listed.any { it.id == IntegrationId.FolderExport && it.configured })
        assertTrue(threads.size >= 4, "folder and webhook checks ran in both calls: $threads")
        threads.forEach { assertNotEquals(callerThread, it, "check ran on the caller thread") }
    }
}
