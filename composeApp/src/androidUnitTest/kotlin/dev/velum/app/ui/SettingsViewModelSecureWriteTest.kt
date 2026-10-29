package dev.velum.app.ui

import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.velum.ai.createOpenAiCompatibleClient
import dev.velum.core.security.SecureRead
import dev.velum.core.security.SecureStore
import dev.velum.core.settings.SettingsRepository
import java.security.InvalidAlgorithmParameterException
import java.util.Collections
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
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
}
