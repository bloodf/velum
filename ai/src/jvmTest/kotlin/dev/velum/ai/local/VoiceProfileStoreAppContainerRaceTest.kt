package dev.velum.ai.local

import dev.velum.core.security.InMemorySecureStore
import dev.velum.core.security.SecureRead
import dev.velum.core.security.SecureStore
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Deterministic repro of MainScreenshotsTest "key not primed" at upsert:
 * [AppContainer] only launches [VoiceProfileStore.ensureKey] asynchronously,
 * while screenshot seeding calls sync [VoiceProfileStore.upsert] immediately.
 *
 * When SecureStore is still blocked inside ensureKey, upsert must fail with
 * the same IllegalStateException — proving the race is missing an await of
 * ensureKey before cipher use (not flaky scheduling alone).
 */
class VoiceProfileStoreAppContainerRaceTest {
    @Test
    fun upsert_before_ensureKey_completes_throws_key_not_primed() {
        val dir = File.createTempFile("velum-vp-race", "").also { it.delete(); it.mkdirs() }
        val gate = CountDownLatch(1)
        val store = GatedSecureStore(release = gate)
        val voices = VoiceProfileStore(
            root = ModelStoreDir(dir.absolutePath),
            secureStore = store,
        )

        // Mirror AppContainer.init: fire-and-forget ensureKey on a background scope.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch { runCatching { voices.ensureKey() } }

        // Wait until ensureKey has entered getString — primedKey is still null.
        assertTrue(
            store.enteredGetLatch.await(5, TimeUnit.SECONDS),
            "ensureKey never entered SecureStore.getString",
        )

        val ex = assertFailsWith<IllegalStateException> {
            voices.upsert("Heitor", FloatArray(8) { 0.1f })
        }
        assertTrue(
            ex.message!!.contains("key not primed"),
            "expected key-not-primed, got: ${ex.message}",
        )

        gate.countDown()
        runBlocking { voices.ensureKey() }
        assertNotNull(voices.upsert("Heitor", FloatArray(8) { 0.1f }))
    }

    @Test
    fun upsert_after_awaiting_ensureKey_succeeds() {
        val dir = File.createTempFile("velum-vp-await", "").also { it.delete(); it.mkdirs() }
        val voices = VoiceProfileStore(
            root = ModelStoreDir(dir.absolutePath),
            secureStore = InMemorySecureStore(),
        )
        // Mirror the MainScreenshotsTest fix: await ensureKey before upsert.
        runBlocking { voices.ensureKey() }
        assertNotNull(voices.upsert("Marina", FloatArray(8) { 0.2f }))
    }

    /**
     * SecureStore that blocks the first [getString] until [release] counts down,
     * simulating a slow Keystore-backed prime racing a sync upsert.
     */
    private class GatedSecureStore(
        private val release: CountDownLatch,
    ) : SecureStore {
        private val memory = InMemorySecureStore()
        val enteredGet = AtomicReference(false)
        val enteredGetLatch = CountDownLatch(1)

        override suspend fun getString(key: String): String? {
            enteredGet.set(true)
            enteredGetLatch.countDown()
            check(release.await(10, TimeUnit.SECONDS)) { "gate timed out" }
            return memory.getString(key)
        }

        override suspend fun putString(key: String, value: String) =
            memory.putString(key, value)

        override suspend fun read(key: String): SecureRead = memory.read(key)

        override suspend fun remove(key: String) = memory.remove(key)

        override suspend fun contains(key: String): Boolean = memory.contains(key)

        override suspend fun wipe() = memory.wipe()
    }
}
