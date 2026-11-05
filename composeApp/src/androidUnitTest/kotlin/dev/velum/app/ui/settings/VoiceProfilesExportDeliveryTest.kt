package dev.velum.app.ui.settings

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.velum.ai.local.ModelStoreDir
import dev.velum.ai.local.VoiceProfileStore
import dev.velum.app.di.AppContainer
import dev.velum.app.i18n.En
import dev.velum.app.i18n.LocalStrings
import dev.velum.app.platform.ShareTextHolder
import dev.velum.app.ui.theme.VelumTheme
import dev.velum.core.data.DriverFactory
import dev.velum.core.security.InMemorySecureStore
import dev.velum.core.security.SecureStore
import java.nio.file.Files
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

/**
 * #107 finding 2a: the Voice Profiles screen used to render `exportEncrypted()` through `take(400)` in a
 * plain (not selectable) Text, and offered no copy, share or save action, so a normal backup could never be
 * taken out of the app. The screen now delivers the whole encrypted payload through an explicit copy action.
 *
 * The fixture is synthetic: one 256-float profile, whose export is far longer than the 400 characters the old
 * preview could show. The test presses the real button and reads the real platform clipboard, so it fails if
 * the delivered string is the preview instead of the export. The delivered payload is then imported into a
 * second store under the same key, which only works when the payload is complete.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class, sdk = [34], qualifiers = "w320dp-h640dp-xxhdpi")
class VoiceProfilesExportDeliveryTest {
    @get:Rule val compose = createEmptyComposeRule()

    /** 256 floats: the export of this profile cannot fit in the 400-character preview that was removed. */
    private val embedding = FloatArray(256) { ((it % 17) - 8) / 8f }

    @Test
    fun the_copy_action_delivers_the_whole_export_and_it_restores_the_profiles() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        // The Android clipboard implementation reads the app context from here; the screen itself does not.
        ShareTextHolder.context = app
        val memory = InMemorySecureStore()
        val source = newRoot()
        runBlocking {
            VoiceProfileStore(source, memory).withReady { upsert("Synthetic Export", embedding, id = "export") }
        }
        val exportLength = runBlocking { VoiceProfileStore(source, memory).withReady { exportEncrypted().length } }
        assertTrue(exportLength > 400, "the fixture must not fit in the old 400-character preview, was $exportLength")

        screen(app, memory, source) {
            tap(En.ux.voiceProfilesExport)
            awaitLabel(En.ux.voiceProfilesExportReady)
            tap(En.added.voiceProfilesExportCopy)
            awaitLabel(En.added.voiceProfilesExportCopied)
        }

        val delivered = assertNotNull(clipboardText(app), "the copy action must put the export on the clipboard")
        assertTrue(delivered.length > 400, "the delivered payload is a preview, not the export: ${delivered.length}")
        assertTrue(delivered != delivered.take(400) + "…", "the delivered payload is the old preview")
        assertTrue(delivered.trimEnd().endsWith("}"), "the delivered payload is not a complete export")

        // Only a complete payload restores the profile: the truncated preview cannot be imported at all.
        val target = newRoot()
        val imported = runBlocking { VoiceProfileStore(target, memory).withReady { importEncrypted(delivered) } }
        assertEquals(1, imported, "the delivered payload must import as one whole profile")
        val restored = runBlocking { VoiceProfileStore(target, memory).withReady { listProfiles() } }.single()
        assertEquals("export", restored.id)
        assertEquals("Synthetic Export", restored.displayName)
        assertEquals(256, restored.embedding.size)
        assertContentEquals(embedding, restored.embedding)

        target.deleteRecursively()
        source.deleteRecursively()
    }

    // ---- helpers ------------------------------------------------------------

    private fun newRoot() = ModelStoreDir(Files.createTempDirectory("velum-voice-export-test").toString())

    @Suppress("DEPRECATION")
    private fun clipboardText(app: Application): String? {
        val manager = app.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        val clip = manager.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).text?.toString()
    }

    private fun awaitLabel(label: String) = compose.waitUntil(10_000) {
        compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty() ||
            compose.onAllNodesWithContentDescription(label).fetchSemanticsNodes().isNotEmpty()
    }

    private fun target(label: String): SemanticsNodeInteraction =
        if (compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty()) compose.onAllNodesWithText(label)[0]
        else compose.onAllNodesWithContentDescription(label)[0]

    private fun tap(label: String) {
        awaitLabel(label)
        runCatching { target(label).performScrollTo() }
        target(label).performSemanticsAction(SemanticsActions.OnClick) { it() }
    }

    private fun screen(app: Application, secure: SecureStore, root: ModelStoreDir, block: () -> Unit) {
        app.deleteDatabase("velum.db")
        val container = AppContainer(secure, DriverFactory(app), modelStoreRoot = root)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent {
                CompositionLocalProvider(LocalStrings provides En) {
                    VelumTheme(darkTheme = false) {
                        VoiceProfilesScreen(container, onBack = {})
                    }
                }
            }
            block()
        } finally {
            activity.pause().stop().destroy()
            container.databaseHandle.driver.close()
        }
    }
}
