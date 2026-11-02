package dev.velum.app.di

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.isRoot
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.velum.ai.local.ModelStoreDir
import dev.velum.ai.local.VoiceProfileStore
import dev.velum.app.i18n.En
import dev.velum.app.i18n.LocalStrings
import dev.velum.app.i18n.PtBr
import dev.velum.app.ui.settings.VoiceProfilesScreen
import dev.velum.app.ui.theme.VelumTheme
import dev.velum.core.data.DriverFactory
import dev.velum.core.security.InMemorySecureStore
import dev.velum.core.security.SecureStore
import dev.velum.core.security.SecureStoreUnavailableException
import java.nio.file.Files
import java.security.ProviderException
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

/**
 * #87 in English, at phone width: the empty, unreadable-profile, wrong-key and storage-error states and the
 * profile editor. Each state must show its English copy, leak no Portuguese string, give every actionable control a
 * name for accessibility, and keep every control inside the screen width (no horizontally clipped controls).
 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalEncodingApi::class)
@Config(application = android.app.Application::class, sdk = [34], qualifiers = "w320dp-h640dp-xxhdpi")
class VoiceProfilesEnglishA11yTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val s = En
    private val chromeKeys = listOf(
        s.save to PtBr.save, s.delete to PtBr.delete, s.cancel to PtBr.cancel, s.name to PtBr.name, s.notes to PtBr.notes,
        s.ux.role to PtBr.ux.role, s.ux.company to PtBr.ux.company, s.ux.email to PtBr.ux.email,
        s.ux.deleteProfile to PtBr.ux.deleteProfile, s.ux.retryProcessing to PtBr.ux.retryProcessing,
        s.ux.back to PtBr.ux.back, s.voiceProfiles to PtBr.voiceProfiles,
    )
    private val voiceKeys = listOf(
        s.ux.voiceProfilesHint to PtBr.ux.voiceProfilesHint,
        s.ux.voiceProfilesEmpty to PtBr.ux.voiceProfilesEmpty,
        s.ux.voiceProfilesUnreadableTitle to PtBr.ux.voiceProfilesUnreadableTitle,
        s.ux.voiceProfilesMissingEmbedding to PtBr.ux.voiceProfilesMissingEmbedding,
        s.ux.voiceProfilesKeyUnavailable to PtBr.ux.voiceProfilesKeyUnavailable,
        s.ux.secureStorageUnavailable to PtBr.ux.secureStorageUnavailable,
        s.ux.voiceProfilesRemoveFiles to PtBr.ux.voiceProfilesRemoveFiles,
        s.ux.voiceProfilesExport to PtBr.ux.voiceProfilesExport,
        s.ux.voiceProfilesImportTitle to PtBr.ux.voiceProfilesImportTitle,
        s.ux.voiceProfilesImport to PtBr.ux.voiceProfilesImport,
        s.ux.voiceProfilesWipe to PtBr.ux.voiceProfilesWipe,
        s.ux.voiceProfilesThreshold to PtBr.ux.voiceProfilesThreshold,
        s.ux.voiceProfilesEnrollHint to PtBr.ux.voiceProfilesEnrollHint,
        s.ux.voiceProfilesSensitivityBalanced to PtBr.ux.voiceProfilesSensitivityBalanced,
        s.ux.voiceProfilesSensitivityStrict to PtBr.ux.voiceProfilesSensitivityStrict,
    )

    private fun labelsOf(): List<Pair<String, androidx.compose.ui.semantics.SemanticsNode>> =
        compose.onAllNodes(androidx.compose.ui.test.isRoot()).fetchSemanticsNodes().flatMap { root ->
            fun walk(n: androidx.compose.ui.semantics.SemanticsNode): List<androidx.compose.ui.semantics.SemanticsNode> =
                listOf(n) + n.children.flatMap { walk(it) }
            walk(root)
        }.map { node ->
            val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }.orEmpty()
            val desc = node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ").orEmpty()
            (text + " " + desc).trim() to node
        }

    private fun assertCleanState(name: String) {
        val nodes = labelsOf()
        val allText = nodes.map { it.first }
        // No Portuguese copy: a Portuguese string on screen that English does not share.
        for ((en, pt) in chromeKeys + voiceKeys) {
            if (pt != en) assertTrue(allText.none { it.contains(pt) }, "$name: Portuguese text on screen: $pt")
        }
        // Every control that can be clicked has a name for screen readers.
        nodes.filter { (_, n) -> n.config.contains(SemanticsActions.OnClick) }.forEach { (label, n) ->
            assertTrue(label.isNotBlank(), "$name: unlabeled clickable at ${n.boundsInRoot}")
        }
        // Nothing is clipped at the screen edges.
        val width = nodes.first().second.boundsInRoot.right.takeIf { it > 0f }
            ?: compose.onAllNodes(isRoot()).fetchSemanticsNodes().first().boundsInRoot.right
        nodes.filter { (label, n) -> label.isNotBlank() && n.boundsInRoot.width > 0f }.forEach { (label, n) ->
            assertTrue(n.boundsInRoot.left >= -0.5f && n.boundsInRoot.right <= width + 0.5f, "$name: '$label' is clipped: ${n.boundsInRoot} in width $width")
        }
    }

    private fun awaitText(text: String) = compose.waitUntil(10_000) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    private fun screen(secure: SecureStore, root: ModelStoreDir, block: () -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("velum.db")
        val container = AppContainer(secure, DriverFactory(context), modelStoreRoot = root)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent {
                CompositionLocalProvider(LocalStrings provides En) {
                    VelumTheme(darkTheme = false) { VoiceProfilesScreen(container, onBack = {}) }
                }
            }
            block()
        } finally {
            activity.pause().stop().destroy()
            container.databaseHandle.driver.close()
        }
    }

    private fun newRoot() = ModelStoreDir(Files.createTempDirectory("velum-voice-en-test").toString())

    @Test
    fun empty_state_is_english_labelled_and_unclipped() {
        val root = newRoot()
        screen(InMemorySecureStore(), root) {
            awaitText(s.ux.voiceProfilesEmpty)
            compose.onNodeWithText(s.ux.voiceProfilesEnrollHint).assertExists()
            assertCleanState("empty")
        }
        root.deleteRecursively()
    }

    @Test
    fun editor_fields_and_buttons_have_english_names() {
        val root = newRoot()
        val memory = InMemorySecureStore()
        runBlocking { VoiceProfileStore(root, memory).withReady { upsert("Synthetic Healthy", floatArrayOf(1f, 0f), id = "healthy") } }
        screen(memory, root) {
            awaitText("Synthetic Healthy")
            compose.onNodeWithText("Synthetic Healthy").performClick()
            for (field in listOf(s.name, s.ux.role, s.ux.company, s.ux.email, s.notes)) {
                compose.onNodeWithContentDescription(field).performScrollTo().assertExists()
            }
            compose.onNodeWithContentDescription(s.save).performScrollTo().assertExists()
            compose.onNodeWithContentDescription(s.ux.deleteProfile).performScrollTo().assertExists()
            compose.onNodeWithContentDescription(s.ux.voiceProfilesImportTitle).performScrollTo().assertExists()
            assertCleanState("editor")
        }
        root.deleteRecursively()
    }

    @Test
    fun unreadable_profile_state_is_english_labelled_and_unclipped() {
        val root = newRoot()
        val memory = InMemorySecureStore()
        runBlocking { VoiceProfileStore(root, memory).withReady { upsert("Synthetic Healthy", floatArrayOf(1f, 0f), id = "healthy") } }
        root.resolve("voices/profiles/orphan.json").writeBytes(
            """{"id":"orphan","displayName":"Synthetic Orphan","dim":2,"encrypted":true}""".encodeToByteArray(),
        )
        screen(memory, root) {
            awaitText(s.ux.voiceProfilesUnreadableTitle)
            compose.onNodeWithText(s.ux.voiceProfilesMissingEmbedding).assertExists()
            compose.onNodeWithContentDescription(s.ux.voiceProfilesRemoveFiles).performScrollTo().assertExists()
            assertCleanState("unreadable")
        }
        root.deleteRecursively()
    }

    @Test
    fun wrong_key_and_storage_errors_are_english_with_a_labelled_retry() {
        val root = newRoot()
        val memory = InMemorySecureStore()
        runBlocking { VoiceProfileStore(root, memory).withReady { upsert("Synthetic Healthy", floatArrayOf(1f, 0f), id = "healthy") } }
        // Wrong key: the stored profiles cannot be opened.
        runBlocking { memory.putString(VoiceProfileStore.PROFILE_KEY_SECURE, Base64.encode(ByteArray(32) { 37 })) }
        screen(memory, root) {
            awaitText(s.ux.voiceProfilesKeyUnavailable)
            compose.onNodeWithContentDescription(s.ux.retryProcessing).performScrollTo().assertExists()
            assertCleanState("wrong-key")
        }
        // Secure storage unavailable.
        val unavailable = object : SecureStore by memory {
            override suspend fun getString(key: String): String? {
                if (key == VoiceProfileStore.PROFILE_KEY_SECURE) throw SecureStoreUnavailableException(ProviderException("synthetic"))
                return memory.getString(key)
            }
        }
        screen(unavailable, root) {
            awaitText(s.ux.secureStorageUnavailable)
            compose.onNodeWithContentDescription(s.ux.retryProcessing).performScrollTo().assertExists()
            assertCleanState("storage-unavailable")
        }
        root.deleteRecursively()
    }
}
