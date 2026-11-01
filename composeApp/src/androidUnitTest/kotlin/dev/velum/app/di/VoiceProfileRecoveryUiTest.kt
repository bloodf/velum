package dev.velum.app.di

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.velum.ai.local.ModelStoreDir
import dev.velum.ai.local.VoiceProfileStore
import dev.velum.ai.local.walkFiles
import dev.velum.app.i18n.PtBr
import dev.velum.app.ui.settings.VoiceProfilesScreen
import dev.velum.app.ui.theme.VelumTheme
import dev.velum.core.data.DriverFactory
import dev.velum.core.security.InMemorySecureStore
import dev.velum.core.security.SecureStore
import dev.velum.core.security.SecureStoreUnavailableException
import java.nio.file.Files
import java.security.ProviderException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class, sdk = [34])
@OptIn(ExperimentalEncodingApi::class)
class VoiceProfileRecoveryUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun valid_profile_can_be_opened_while_orphan_is_visible_and_removal_requires_confirmation() = fixture {
        orphan()
        val before = snapshot(); val key = runBlocking { memory.getString(VoiceProfileStore.PROFILE_KEY_SECURE) }
        screen(memory) {
            awaitText("Synthetic Healthy")
            compose.onNodeWithText(PtBr.ux.voiceProfilesUnreadableTitle).assertExists()
            compose.onNodeWithText(PtBr.ux.voiceProfilesMissingEmbedding).assertExists()
            compose.onNodeWithText("Synthetic Healthy").performClick()
            compose.onNodeWithContentDescription(PtBr.save).assertExists()
            compose.onNodeWithContentDescription(PtBr.ux.voiceProfilesRemoveFiles).performScrollTo().performClick()
            compose.onNodeWithText(PtBr.cancel).performClick()
            assertSnapshot(before)
            compose.onNodeWithContentDescription(PtBr.ux.voiceProfilesRemoveFiles).performScrollTo().performClick()
            compose.onNodeWithText(PtBr.delete).performClick()
            awaitText(PtBr.ux.voiceProfilesDeleted)
            compose.onNodeWithText(PtBr.ux.voiceProfilesUnreadableTitle).assertDoesNotExist()
            assertSnapshot(before - "orphan.json")
        }
        assertEquals(key, runBlocking { memory.getString(VoiceProfileStore.PROFILE_KEY_SECURE) })
        assertEquals("healthy", runBlocking { store.withReady { match(floatArrayOf(1f, 0f)) } }!!.profile.id)
    }

    @Test
    fun repairing_a_profile_while_confirmation_is_open_preserves_the_repaired_files() = fixture {
        runBlocking { store.withReady { upsert("Synthetic Repair", floatArrayOf(0f, 1f), id = "orphan") } }
        val encrypted = profiles.resolve("orphan.enc").readBytes()
        profiles.resolve("orphan.enc").deleteRecursively()
        screen(memory) {
            awaitText(PtBr.ux.voiceProfilesUnreadableTitle)
            compose.onNodeWithContentDescription(PtBr.ux.voiceProfilesRemoveFiles).performScrollTo().performClick()
            profiles.resolve("orphan.enc").writeBytes(encrypted)
            val repaired = snapshot()
            compose.onNodeWithText(PtBr.delete).performClick()
            awaitText(PtBr.ux.voiceProfilesFilesChanged)
            compose.onNodeWithText(PtBr.ux.voiceProfilesUnreadableTitle).assertDoesNotExist()
            compose.onNodeWithText("Synthetic Repair").assertExists()
            assertSnapshot(repaired)
        }
    }

    @Test
    fun wrong_key_is_a_global_retryable_error_and_restoring_the_key_recovers_without_file_changes() = fixture {
        val key = runBlocking { memory.getString(VoiceProfileStore.PROFILE_KEY_SECURE) }!!
        val before = snapshot()
        runBlocking { memory.putString(VoiceProfileStore.PROFILE_KEY_SECURE, Base64.encode(ByteArray(32) { 37 })) }
        screen(memory) {
            awaitText(PtBr.ux.voiceProfilesKeyUnavailable)
            compose.onNodeWithText(PtBr.ux.voiceProfilesUnreadableTitle).assertDoesNotExist()
            compose.onNodeWithContentDescription(PtBr.ux.voiceProfilesRemoveFiles).assertDoesNotExist()
            compose.onNodeWithText(PtBr.ux.voiceProfilesEmpty).assertDoesNotExist()
            assertSnapshot(before)
            runBlocking { memory.putString(VoiceProfileStore.PROFILE_KEY_SECURE, key) } // synthetic owner restore
            compose.onNodeWithContentDescription(PtBr.ux.retryProcessing).performScrollTo().performClick()
            awaitText("Synthetic Healthy")
            compose.onNodeWithText(PtBr.ux.voiceProfilesKeyUnavailable).assertDoesNotExist()
            assertSnapshot(before)
        }
        assertEquals(key, runBlocking { memory.getString(VoiceProfileStore.PROFILE_KEY_SECURE) })
    }

    @Test
    fun transient_vault_error_cannot_offer_file_removal_and_retry_recovers_the_same_screen() = fixture {
        val before = snapshot(); val fail = AtomicBoolean(true)
        val unavailable = object : SecureStore by memory {
            override suspend fun getString(key: String): String? {
                if (key == VoiceProfileStore.PROFILE_KEY_SECURE && fail.get()) {
                    throw SecureStoreUnavailableException(ProviderException("Synthetic transient failure"))
                }
                return memory.getString(key)
            }
        }
        screen(unavailable) {
            awaitText(PtBr.ux.secureStorageUnavailable)
            compose.onNodeWithText(PtBr.ux.voiceProfilesUnreadableTitle).assertDoesNotExist()
            compose.onNodeWithContentDescription(PtBr.ux.voiceProfilesRemoveFiles).assertDoesNotExist()
            assertSnapshot(before)
            fail.set(false)
            compose.onNodeWithContentDescription(PtBr.ux.retryProcessing).performScrollTo().performClick()
            awaitText("Synthetic Healthy")
            compose.onNodeWithText(PtBr.ux.secureStorageUnavailable).assertDoesNotExist()
            assertSnapshot(before)
        }
    }

    private fun awaitText(text: String) = compose.waitUntil(10_000) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    private fun fixture(block: Fixture.() -> Unit) {
        val fixture = Fixture()
        try { fixture.block() } finally { fixture.root.deleteRecursively() }
    }

    private inner class Fixture {
        val root = ModelStoreDir(Files.createTempDirectory("velum-profile-recovery-ui-test").toString())
        val memory = InMemorySecureStore()
        val store = VoiceProfileStore(root, memory)
        val profiles get() = root.resolve("voices/profiles")
        init { runBlocking { store.withReady { upsert("Synthetic Healthy", floatArrayOf(1f, 0f), id = "healthy") } } }
        fun orphan() = profiles.resolve("orphan.json").writeBytes(
            """{"id":"orphan","displayName":"Synthetic Orphan","dim":2,"encrypted":true}""".encodeToByteArray(),
        )
        fun snapshot() = profiles.walkFiles().associate { it.name to it.readBytes() }
        fun assertSnapshot(expected: Map<String, ByteArray>) {
            val actual = snapshot()
            assertEquals(expected.keys, actual.keys)
            expected.forEach { (name, bytes) -> assertContentEquals(bytes, actual.getValue(name), name) }
        }
        fun screen(secure: SecureStore, assertions: () -> Unit) {
            val context = ApplicationProvider.getApplicationContext<Context>()
            context.deleteDatabase("velum.db")
            val container = AppContainer(secure, DriverFactory(context), modelStoreRoot = root)
            val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            try {
                activity.get().setContent { VelumTheme(darkTheme = false) { VoiceProfilesScreen(container, onBack = {}) } }
                assertions()
            } finally { activity.pause().stop().destroy() }
        }
    }
}
