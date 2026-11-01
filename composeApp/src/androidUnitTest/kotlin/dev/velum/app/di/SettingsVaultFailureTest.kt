package dev.velum.app.di

import android.app.Application
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.velum.ai.local.ModelStoreDir
import dev.velum.app.i18n.PtBr
import dev.velum.app.ui.settings.ApiSettingsScreen
import dev.velum.app.ui.theme.VelumTheme
import dev.velum.core.data.DriverFactory
import dev.velum.core.security.InMemorySecureStore
import dev.velum.core.security.SecureKeys
import dev.velum.core.security.SecureStore
import dev.velum.core.security.SecureStoreUnavailableException
import dev.velum.core.settings.SettingsLoadState
import dev.velum.core.settings.SettingsRepository
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [34])
class SettingsVaultFailureTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun unavailable_vault_on_startup_shows_retry_instead_of_missing_credentials_and_recovers_same_container() {
        val fixture = Fixture(failReads = true)
        try {
            fixture.withApiScreen {
                compose.waitUntil(10_000) { fixture.container.settingsRepository.loadState.value == SettingsLoadState.Unavailable }
                compose.onNodeWithText(PtBr.ux.secureStorageUnavailable).assertIsDisplayed()
                compose.onNodeWithContentDescription(PtBr.ux.retryProcessing).assertIsDisplayed()
                assertEquals("storage_unavailable", fixture.container.settingsViewModel.ui.value.error)
                assertEquals(0, fixture.writes)
                fixture.readFailure.set(false)
                compose.onNodeWithContentDescription(PtBr.ux.retryProcessing).performClick()
                compose.waitUntil(10_000) { fixture.container.settingsRepository.loadState.value == SettingsLoadState.Ready }
                compose.onNodeWithText(PtBr.ux.secureStorageUnavailable).assertDoesNotExist()
                assertTrue(fixture.container.settingsViewModel.settings.value.hasApiKey)
                assertEquals("synthetic-api", runBlocking { fixture.memory.getString(SecureKeys.AI_API_KEY) })
                assertEquals("synthetic-voice", runBlocking { fixture.memory.getString(SecureKeys.VOICE_PROFILE_AES_KEY) })
            }
        } finally { fixture.close() }
    }

    @Test
    fun failed_settings_write_is_visible_preserves_snapshot_and_can_retry_without_restarting_app() {
        val fixture = Fixture(failReads = false)
        try {
            fixture.withApiScreen {
                compose.waitUntil(10_000) { fixture.container.settingsRepository.loadState.value == SettingsLoadState.Ready }
                fixture.writeFailure.set(true)
                compose.runOnIdle { fixture.container.settingsViewModel.setApiKey("synthetic-replacement") }
                compose.waitUntil(10_000) { fixture.container.settingsRepository.loadState.value == SettingsLoadState.Unavailable }
                compose.onNodeWithText(PtBr.ux.secureStorageUnavailable).assertIsDisplayed()
                assertTrue(fixture.container.settingsRepository.settings.value.hasApiKey)
                assertEquals("synthetic-api", runBlocking { fixture.memory.getString(SecureKeys.AI_API_KEY) })
                assertEquals("synthetic-voice", runBlocking { fixture.memory.getString(SecureKeys.VOICE_PROFILE_AES_KEY) })
                fixture.writeFailure.set(false)
                compose.onNodeWithContentDescription(PtBr.ux.retryProcessing).performClick()
                compose.waitUntil(10_000) { fixture.container.settingsRepository.loadState.value == SettingsLoadState.Ready }
                compose.runOnIdle { fixture.container.settingsViewModel.setApiKey("synthetic-replacement") }
                compose.waitUntil(10_000) { runBlocking { fixture.memory.getString(SecureKeys.AI_API_KEY) } == "synthetic-replacement" }
                assertEquals("synthetic-voice", runBlocking { fixture.memory.getString(SecureKeys.VOICE_PROFILE_AES_KEY) })
            }
        } finally { fixture.close() }
    }

    @Test
    fun shared_settings_action_failure_does_not_crash_or_report_a_preference_as_saved() {
        val fixture = Fixture(failReads = false)
        try {
            fixture.withApiScreen {
                compose.waitUntil(10_000) { fixture.container.settingsRepository.loadState.value == SettingsLoadState.Ready }
                val before = fixture.container.settingsRepository.preferences.value
                fixture.writeFailure.set(true)
                compose.runOnIdle {
                    fixture.container.settingsViewModel.runStorageAction {
                        fixture.container.settingsRepository.setThemeMode("dark")
                        error("Must not reach the saved/success callback")
                    }
                }
                compose.waitUntil(10_000) { fixture.container.settingsRepository.loadState.value == SettingsLoadState.Unavailable }
                assertEquals(before, fixture.container.settingsRepository.preferences.value)
                compose.onNodeWithText(PtBr.ux.secureStorageUnavailable).assertIsDisplayed()
            }
        } finally { fixture.close() }
    }

    private inner class Fixture(failReads: Boolean) {
        val memory = InMemorySecureStore()
        val readFailure = AtomicBoolean(failReads)
        val writeFailure = AtomicBoolean(false)
        var writes = 0
        val root = ModelStoreDir(Files.createTempDirectory("velum-vault-startup-test").toString())
        val container: AppContainer
        init {
            val context = ApplicationProvider.getApplicationContext<Context>()
            context.deleteDatabase("velum.db")
            runBlocking {
                memory.putString(SecureKeys.AI_API_KEY, "synthetic-api")
                memory.putString(SecureKeys.VOICE_PROFILE_AES_KEY, "synthetic-voice")
                SettingsRepository(memory).load()
            }
            val faulting = object : SecureStore by memory {
                override suspend fun getString(key: String): String? {
                    if (readFailure.get()) throw SecureStoreUnavailableException(IllegalStateException("synthetic provider"))
                    return memory.getString(key)
                }
                override suspend fun putString(key: String, value: String) {
                    if (writeFailure.get()) throw SecureStoreUnavailableException(IllegalStateException("synthetic write"))
                    writes++
                    memory.putString(key, value)
                }
            }
            container = AppContainer(faulting, DriverFactory(context), modelStoreRoot = root)
        }
        fun withApiScreen(assertions: () -> Unit) {
            val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            try {
                activity.get().setContent { VelumTheme(darkTheme = false) { ApiSettingsScreen(container, onBack = {}) } }
                assertions()
            } finally { activity.pause().stop().destroy() }
        }
        fun close() { root.deleteRecursively() }
    }
}
