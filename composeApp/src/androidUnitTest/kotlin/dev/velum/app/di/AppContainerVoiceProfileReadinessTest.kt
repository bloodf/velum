package dev.velum.app.di

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import dev.velum.app.i18n.PtBr
import dev.velum.app.ui.settings.VoiceProfilesScreen
import dev.velum.app.ui.theme.VelumTheme
import org.junit.Rule
import org.robolectric.Robolectric
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.velum.ai.local.ModelStoreDir
import dev.velum.ai.local.VoiceProfileStore
import dev.velum.core.data.DriverFactory
import dev.velum.core.security.InMemorySecureStore
import dev.velum.core.security.SecureStore
import java.nio.file.Files
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class, sdk = [34])
class AppContainerVoiceProfileReadinessTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @Test
    fun cold_voice_screen_remains_responsive_and_displays_existing_profile_after_readiness() {
        val root = ModelStoreDir(Files.createTempDirectory("velum-voice-screen-test").toString())
        val memory = InMemorySecureStore()
        runBlocking { VoiceProfileStore(root, memory).withReady { upsert("Synthetic Alice", floatArrayOf(1f, 0f)) } }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val gated = object : SecureStore by memory {
            override suspend fun getString(key: String): String? {
                if (key == VoiceProfileStore.PROFILE_KEY_SECURE) {
                    entered.complete(Unit)
                    release.await()
                }
                return memory.getString(key)
            }
        }
        try {
            withVoiceScreen(root, gated) {
                compose.waitUntil(10_000) { entered.isCompleted }
                compose.onNodeWithText("Synthetic Alice").assertDoesNotExist()
                compose.onNodeWithText(PtBr.ux.voiceProfilesEmpty).assertDoesNotExist()
                // This callback executes on the UI while secure-store access is suspended.
                compose.runOnIdle { assertFalse(release.isCompleted) }
                release.complete(Unit)
                compose.waitUntil(10_000) {
                    compose.onAllNodesWithText("Synthetic Alice").fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithText("Synthetic Alice").assertIsDisplayed()
            }
        } finally {
            release.complete(Unit)
            root.deleteRecursively()
        }
    }

    @Test
    fun invalid_key_is_visible_as_an_error_instead_of_an_empty_profile_list() {
        val root = ModelStoreDir(Files.createTempDirectory("velum-voice-screen-error-test").toString())
        val memory = InMemorySecureStore()
        runBlocking { memory.putString(VoiceProfileStore.PROFILE_KEY_SECURE, "invalid synthetic key") }
        try {
            withVoiceScreen(root, memory) {
                compose.waitUntil(10_000) {
                    compose.onAllNodesWithText(PtBr.ux.voiceProfilesKeyUnavailable).fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithText(PtBr.ux.voiceProfilesKeyUnavailable).assertIsDisplayed()
                compose.onNodeWithText(PtBr.ux.voiceProfilesEmpty).assertDoesNotExist()
            }
            assertEquals("invalid synthetic key", runBlocking { memory.getString(VoiceProfileStore.PROFILE_KEY_SECURE) })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun returning_to_voice_screen_retries_transient_failure_using_the_same_store() {
        val root = ModelStoreDir(Files.createTempDirectory("velum-voice-screen-retry-test").toString())
        val memory = InMemorySecureStore()
        runBlocking { VoiceProfileStore(root, memory).withReady { upsert("Synthetic Retry", floatArrayOf(1f, 0f)) } }
        val savedBlob = root.resolve("voices/profiles/synthetic-retry.enc").readBytes()
        val failRead = java.util.concurrent.atomic.AtomicBoolean(true)
        val transient = object : SecureStore by memory {
            override suspend fun getString(key: String): String? {
                if (key == VoiceProfileStore.PROFILE_KEY_SECURE && failRead.get()) {
                    error("Synthetic transient secure-store failure")
                }
                return memory.getString(key)
            }
        }
        val visible = androidx.compose.runtime.mutableStateOf(true)
        try {
            withVoiceScreen(root, transient, visible) {
                compose.waitUntil(10_000) {
                    compose.onAllNodesWithText(PtBr.ux.genericError).fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithText(PtBr.ux.genericError).assertIsDisplayed()
                // Back disposes this destination; opening it again retries the same store.
                compose.runOnIdle { visible.value = false }
                compose.runOnIdle {
                    failRead.set(false)
                    visible.value = true
                }
                compose.waitUntil(10_000) {
                    compose.onAllNodesWithText("Synthetic Retry").fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithText("Synthetic Retry").assertIsDisplayed()
                compose.onNodeWithText(PtBr.ux.genericError).assertDoesNotExist()
            }
            assertContentEquals(savedBlob, root.resolve("voices/profiles/synthetic-retry.enc").readBytes())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun withVoiceScreen(
        root: ModelStoreDir,
        secure: SecureStore,
        visible: androidx.compose.runtime.State<Boolean>? = null,
        assertions: () -> Unit,
    ) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("velum.db")
        val container = AppContainer(secure, DriverFactory(context), modelStoreRoot = root)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent {
                VelumTheme(darkTheme = false) {
                    if (visible?.value != false) VoiceProfilesScreen(container, onBack = {})
                }
            }
            assertions()
        } finally {
            activity.pause().stop().destroy()
        }
    }

    @Test
    fun immediate_access_after_construction_waits_for_key_and_can_reopen_the_encrypted_profile() = runBlocking {
        withTimeout(15_000) {
            val context = ApplicationProvider.getApplicationContext<Context>()
            context.deleteDatabase("velum.db")
            val root = ModelStoreDir(Files.createTempDirectory("velum-container-voice-test").toString())
            val callerThread = Thread.currentThread()
            val keyThread = java.util.concurrent.atomic.AtomicReference<Thread>()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val memory = InMemorySecureStore()
            val secure = object : SecureStore by memory {
                override suspend fun getString(key: String): String? {
                    if (key == VoiceProfileStore.PROFILE_KEY_SECURE) {
                        keyThread.set(Thread.currentThread())
                        entered.complete(Unit)
                        release.await()
                    }
                    return memory.getString(key)
                }
            }
            val container = AppContainer(secure, DriverFactory(context), modelStoreRoot = root)
            val access = async(start = CoroutineStart.UNDISPATCHED) {
                container.voiceProfileStore.withReady { upsert("Synthetic Alice", floatArrayOf(1f, 0f)) }
            }
            entered.await()
            assertNotSame(callerThread, keyThread.get(), "Secure-store I/O must run off the caller thread")
            assertFalse(access.isCompleted, "First access must suspend, leaving the caller responsive")
            assertFalse(root.resolve("voices").exists())
            release.complete(Unit)
            assertEquals("Synthetic Alice", access.await().displayName)
            val reopened = VoiceProfileStore(root, secure)
            assertContentEquals(floatArrayOf(1f, 0f), reopened.withReady { get("synthetic-alice")!! }.embedding)
            root.deleteRecursively()
        }
    }
}
