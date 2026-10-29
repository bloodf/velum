package dev.velum.app.ui

import android.os.Looper
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.velum.ai.createOpenAiCompatibleClient
import dev.velum.core.security.SecureRead
import dev.velum.core.security.SecureStore
import dev.velum.core.settings.SettingsRepository
import java.security.InvalidAlgorithmParameterException
import java.util.Collections
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * #92: SettingsViewModel.init loads settings and may putString (default profiles).
 * A failing SecureStore write must set a fixed error token and must not deliver an
 * uncaught exception (launch-smoke FATAL on Main.immediate).
 *
 * Uses AndroidJUnit4 + sdk 34 like other composeApp androidUnitTest classes so
 * this suite does not introduce a second Robolectric SDK/runner into the same
 * JVM as GraphicsMode.NATIVE Roborazzi tests.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SettingsViewModelSecureWriteTest {

    @Test
    fun init_failing_putString_sets_error_without_uncaught_exception() {
        val uncaught = Collections.synchronizedList(mutableListOf<Throwable>())
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, t -> uncaught.add(t) }
        try {
            val store = FailingPutSecureStore()
            val vm = SettingsViewModel(
                settingsRepository = SettingsRepository(store),
                aiClient = createOpenAiCompatibleClient(),
            )
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            runBlocking { delay(300) }
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            assertEquals(SettingsViewModel.ERR_SECURE_STORE, vm.ui.value.error)
            assertTrue(uncaught.isEmpty(), "uncaught exceptions: $uncaught")
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }

    @Test
    fun setApiKey_failing_putString_sets_error_without_uncaught_exception() {
        val uncaught = Collections.synchronizedList(mutableListOf<Throwable>())
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, t -> uncaught.add(t) }
        try {
            // Allow init load to succeed (no-op puts), then fail a later write.
            val store = FailingPutSecureStore(failAfter = 3)
            val vm = SettingsViewModel(
                settingsRepository = SettingsRepository(store),
                aiClient = createOpenAiCompatibleClient(),
            )
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            runBlocking { delay(300) }
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            vm.setApiKey("sk-test-not-real")
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            runBlocking { delay(300) }
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            assertEquals(SettingsViewModel.ERR_SECURE_STORE, vm.ui.value.error)
            assertTrue(uncaught.isEmpty(), "uncaught exceptions: $uncaught")
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }

    @Test
    fun setApiKey_cancelled_mid_write_propagates_without_error_state() {
        val hangStore = HangingPutSecureStore()
        val viewModelStore = ViewModelStore()
        val owner = object : ViewModelStoreOwner {
            override val viewModelStore: ViewModelStore = viewModelStore
        }
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass == SettingsViewModel::class.java)
                return SettingsViewModel(
                    settingsRepository = SettingsRepository(hangStore),
                    aiClient = createOpenAiCompatibleClient(),
                ) as T
            }
        }
        val vm = ViewModelProvider(owner, factory)[SettingsViewModel::class.java]
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        runBlocking { delay(300) }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertNull(vm.ui.value.error, "init must not set error before cancellation test")

        hangStore.hangPuts = true
        vm.setApiKey("sk-test-not-real")
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        runBlocking {
            withTimeout(5_000) { hangStore.putEntered.await() }
        }

        viewModelStore.clear()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        runBlocking { delay(200) }
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertTrue(hangStore.putWasCancelled, "secure write must observe cancellation")
        assertNull(vm.ui.value.error, "CancellationException must not become error state")
        assertNull(vm.ui.value.message)
        assertNull(vm.ui.value.errorStatus)
    }

    /**
     * SecureStore that throws on putString after [failAfter] successful writes
     * (0 = fail immediately). Mimics Keystore IV rejection without secrets in the message.
     */
    private class FailingPutSecureStore(
        private val failAfter: Int = 0,
    ) : SecureStore {
        private var puts = 0
        private val memory = mutableMapOf<String, String>()

        override suspend fun putString(key: String, value: String) {
            if (puts >= failAfter) {
                throw InvalidAlgorithmParameterException("Caller-provided IV not permitted")
            }
            puts++
            memory[key] = value
        }

        override suspend fun getString(key: String): String? = memory[key]

        override suspend fun read(key: String): SecureRead =
            memory[key]?.let { SecureRead.Value(it) } ?: SecureRead.Missing

        override suspend fun remove(key: String) {
            memory.remove(key)
        }

        override suspend fun contains(key: String): Boolean = key in memory

        override suspend fun wipe() {
            memory.clear()
        }
    }

    /**
     * Completes ordinary puts until [hangPuts] is armed, then suspends forever so a
     * mid-write cancellation can be observed.
     */
    private class HangingPutSecureStore : SecureStore {
        @Volatile
        var hangPuts: Boolean = false

        val putEntered = CompletableDeferred<Unit>()

        @Volatile
        var putWasCancelled: Boolean = false

        private val memory = mutableMapOf<String, String>()

        override suspend fun putString(key: String, value: String) {
            if (hangPuts) {
                putEntered.complete(Unit)
                try {
                    awaitCancellation()
                } catch (e: CancellationException) {
                    putWasCancelled = true
                    throw e
                }
            }
            memory[key] = value
        }

        override suspend fun getString(key: String): String? = memory[key]

        override suspend fun read(key: String): SecureRead =
            memory[key]?.let { SecureRead.Value(it) } ?: SecureRead.Missing

        override suspend fun remove(key: String) {
            memory.remove(key)
        }

        override suspend fun contains(key: String): Boolean = key in memory

        override suspend fun wipe() {
            memory.clear()
        }
    }
}
