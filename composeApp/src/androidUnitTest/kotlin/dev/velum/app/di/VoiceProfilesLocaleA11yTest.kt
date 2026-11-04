package dev.velum.app.di

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertAll
import androidx.compose.ui.test.isNotEnabled
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.velum.ai.local.ModelStoreDir
import dev.velum.ai.local.VoiceProfileStore
import dev.velum.app.i18n.En
import dev.velum.app.i18n.LocalStrings
import dev.velum.app.i18n.PtBr
import dev.velum.app.i18n.Strings
import dev.velum.app.i18n.UxStrings
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
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

/**
 * #87 in both languages and at normal and 1.3x font scale, at phone width: empty, editor (save, delete),
 * unreadable-profile, wrong-key and storage-error states, plus the sensitivity, export and wipe actions.
 *
 * For every state: the screen's own-language copy shows; no string of the OTHER language (every voiceProfiles*
 * key in UxStrings plus the shared chrome strings) is on screen; every clickable node has a name; and no control
 * is clipped horizontally. A guard requires every voiceProfiles* key to be either seen on screen during the run or
 * listed in [UNREACHED] with a reason, so a new string cannot slip through unchecked.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalEncodingApi::class)
@Config(application = android.app.Application::class, sdk = [34], qualifiers = "w320dp-h640dp-xxhdpi")
class VoiceProfilesLocaleA11yTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun english_normal_font() = runAll(En, PtBr, 1.0f)
    @Test fun english_large_font() = runAll(En, PtBr, 1.3f)
    @Test fun portuguese_normal_font() = runAll(PtBr, En, 1.0f)
    @Test fun portuguese_large_font() = runAll(PtBr, En, 1.3f)

    // ---- keys ---------------------------------------------------------------

    private fun voiceKeys(s: Strings): Map<String, String> =
        UxStrings::class.java.methods
            .filter { it.name.startsWith("getVoiceProfiles") && it.returnType == String::class.java && it.parameterCount == 0 }
            .associate { it.name.removePrefix("get") to it.invoke(s.ux) as String }

    private fun chrome(s: Strings): Map<String, String> = mapOf(
        "save" to s.save, "delete" to s.delete, "cancel" to s.cancel, "confirm" to s.confirm, "name" to s.name,
        "notes" to s.notes, "role" to s.ux.role, "company" to s.ux.company, "email" to s.ux.email,
        "deleteProfile" to s.ux.deleteProfile, "retry" to s.ux.retryProcessing, "back" to s.ux.back,
        "title" to s.voiceProfiles, "confirmDeleteTitle" to s.ux.confirmDeleteTitle,
        "wipeBody" to s.ux.confirmWipeProfilesBody, "deleteBody" to s.ux.confirmDeleteProfileBody,
        "secureStorage" to s.ux.secureStorageUnavailable,
    )

    /**
     * Strings no state here can show, with the reason. Keep this honest: the guard also fails when a key listed
     * here does appear, so the list cannot go stale.
     */
    private val UNREACHED = mapOf(
        "VoiceProfilesFileUnavailable" to "needs a profile file that cannot be read (I/O error)",
        "VoiceProfilesFilesChanged" to "needs files repaired while the removal dialog is open",
        "VoiceProfilesImportedFormat" to "needs importing 2+ profiles; plural text is covered in VoiceProfilesLocalizationTest",
        "VoiceProfilesNoCachedVoice" to "shown by the recording's speaker sheet, not by this screen",
        "VoiceProfilesChangeBlocked" to "needs saving a profile that turned unreadable",
    )

    /** Font scale seen from inside the app's theme, so a nested provider cannot silently reset it. */
    private var observedFontScale = 0f

    @Composable
    private fun FontScaleProbe() { observedFontScale = LocalDensity.current.fontScale }

    /** Keys whose text is the same word in English and Portuguese on purpose. */
    private val SHARED = emptySet<String>()

    // ---- label / bounds helpers --------------------------------------------

    private fun nodes(): List<Pair<String, SemanticsNode>> {
        fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap { walk(it) }
        return compose.onAllNodes(isRoot()).fetchSemanticsNodes().flatMap { walk(it) }.map { node ->
            val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }.orEmpty()
            val desc = node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ").orEmpty()
            (text + " " + desc).trim() to node
        }
    }

    /** Whole-label match, or for format strings (%s / %d) their longest literal part. */
    private fun appearsIn(label: String, labels: List<String>): Boolean {
        if (label.isBlank()) return false
        if (!label.contains('%')) return labels.any { it == label || (label.length >= 12 && it.contains(label)) }
        val literal = label.split(Regex("%[sd]")).maxByOrNull { it.trim().length }.orEmpty().trim()
        return literal.length >= 8 && labels.any { it.contains(literal) }
    }

    private fun exact(label: String, labels: List<String>) = labels.any { it == label }

    private fun assertState(
        name: String,
        own: Strings,
        other: Strings,
        seen: MutableSet<String>,
    ) {
        val all = nodes()
        val labels = all.map { it.first }
        val ownKeys = voiceKeys(own)
        val otherKeys = voiceKeys(other)
        val ownChrome = chrome(own)
        val otherChrome = chrome(other)
        // No string of the other language, unless this language shows the same words.
        for ((key, otherText) in otherKeys + otherChrome.mapKeys { "chrome:" + it.key }) {
            val ownText = ownKeys[key] ?: ownChrome[key.removePrefix("chrome:")]
            // Identical copy in both languages must be a declared, deliberate overlap, or it hides a leak.
            if (ownText == otherText) {
                assertTrue(key in SHARED, "$name: '$key' is identical in both languages ('$otherText'); list it in SHARED if intended")
                continue
            }
            val leaked = if (otherText.contains('%')) appearsIn(otherText, labels) else exact(otherText, labels)
            assertTrue(!leaked, "$name: ${other.localeTag} string on screen for $key: '$otherText'")
        }
        // Every clickable node has a name for screen readers.
        all.filter { (_, n) -> n.config.contains(SemanticsActions.OnClick) }.forEach { (label, n) ->
            assertTrue(label.isNotBlank(), "$name: unlabeled clickable at ${n.boundsInRoot}")
        }
        // No control is clipped at the screen edges.
        val width = compose.onAllNodes(isRoot()).fetchSemanticsNodes().first().boundsInRoot.right
        all.filter { (label, n) -> label.isNotBlank() && n.boundsInRoot.width > 0f }.forEach { (label, n) ->
            assertTrue(
                n.boundsInRoot.left >= -0.5f && n.boundsInRoot.right <= width + 0.5f,
                "$name: '$label' is clipped: ${n.boundsInRoot} in width $width",
            )
        }
        // Sample counts are short ("1 sample", "2 samples"), below appearsIn's length floor: match the whole
        // first segment of the row (the row is merged with the profile name before it), so "1 samples" cannot pass for "1 sample".
        fun sampleRow(text: String) = labels.any { l -> l.split(" · ").first().let { it == text || it.endsWith(" $text") } }
        val sampleRows = mapOf("VoiceProfilesSampleOne" to own.ux.voiceProfilesSampleOne, "VoiceProfilesSamplesFormat" to own.ux.voiceProfilesSamplesFormat.replace("%d", "2"))
        for ((key, text) in sampleRows) if (sampleRow(text)) seen += key
        val otherRows = listOf(other.ux.voiceProfilesSampleOne, other.ux.voiceProfilesSamplesFormat.replace("%d", "2"), other.ux.voiceProfilesSamplesFormat.replace("%d", "1"))
        for (text in otherRows) assertTrue(!sampleRow(text), "$name: ${other.localeTag} sample copy on screen: '$text'")
        // ImportedFormat shares the word "Imported" with the singular status, so its literal would match falsely.
        for ((key, text) in ownKeys) if (key != "VoiceProfilesImportedFormat" && (appearsIn(text, labels) || exact(text, labels))) seen += key
    }

    // ---- driving ------------------------------------------------------------

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

    private fun isSelected(label: String): Boolean {
        val node = nodes().firstOrNull { it.first == label && it.second.config.contains(SemanticsProperties.Selected) }
            ?: return false
        return node.second.config[SemanticsProperties.Selected]
    }

    private fun screen(s: Strings, scale: Float, secure: SecureStore, root: ModelStoreDir, block: () -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("velum.db")
        val container = AppContainer(secure, DriverFactory(context), modelStoreRoot = root)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalStrings provides s,
                    LocalDensity provides Density(density.density, scale),
                ) {
                    VelumTheme(darkTheme = false) {
                        FontScaleProbe()
                        VoiceProfilesScreen(container, onBack = {})
                    }
                }
            }
            block()
            assertEquals(scale, observedFontScale, "font scale reached the screen")
        } finally {
            activity.pause().stop().destroy()
            container.databaseHandle.driver.close()
        }
    }

    private fun newRoot() = ModelStoreDir(Files.createTempDirectory("velum-voice-locale-test").toString())

    private fun withProfile(root: ModelStoreDir, memory: SecureStore) {
        runBlocking { VoiceProfileStore(root, memory).withReady { upsert("Synthetic Healthy", floatArrayOf(1f, 0f), id = "healthy") } }
    }

    /** Secure store whose reads can be made to fail like an unavailable keystore. */
    private class FlakyReads(private val inner: SecureStore) : SecureStore by inner {
        @Volatile var failReads = false
        override suspend fun getString(key: String): String? {
            if (failReads) throw SecureStoreUnavailableException(RuntimeException("synthetic"))
            return inner.getString(key)
        }
    }

    private fun runAll(s: Strings, other: Strings, scale: Float) {
        val seen = mutableSetOf<String>()
        val tag = "${s.localeTag}@$scale"
        val balanced = s.ux.voiceProfilesSensitivityBalanced
        val strict = s.ux.voiceProfilesSensitivityStrict

        // Empty library: hints, sensitivity (selected state), export, wipe.
        newRoot().let { root ->
            screen(s, scale, InMemorySecureStore(), root) {
                awaitLabel(s.ux.voiceProfilesEmpty)
                assertState("$tag empty", s, other, seen)
                compose.waitUntil(10_000) { isSelected(balanced) }
                assertTrue(isSelected(balanced), "$tag: default sensitivity is announced as selected")
                assertTrue(!isSelected(strict))
                tap(strict)
                awaitLabel(s.ux.voiceProfilesThresholdSet)
                compose.waitUntil(10_000) { isSelected(strict) }
                assertTrue(!isSelected(balanced), "$tag: selection moved")
                assertState("$tag sensitivity", s, other, seen)
                tap(s.ux.voiceProfilesExport)
                awaitLabel(s.ux.voiceProfilesExportEmpty)
                assertTrue(nodes().none { (label, _) -> label.trimStart().startsWith("{") }, "$tag: raw JSON shown for an empty export")
                assertState("$tag empty export", s, other, seen)
                tap(s.ux.voiceProfilesWipe)
                awaitLabel(s.ux.confirmWipeProfilesBody)
                assertState("$tag wipe dialog", s, other, seen)
                tap(s.confirm)
                awaitLabel(s.ux.voiceProfilesWiped)
                assertState("$tag wiped", s, other, seen)
            }
            root.deleteRecursively()
        }

        // Editor: fields, save, delete.
        newRoot().let { root ->
            val memory = InMemorySecureStore()
            withProfile(root, memory)
            // Two samples, so the plural copy shows next to the singular one.
            runBlocking { VoiceProfileStore(root, memory).withReady { repeat(2) { upsert("Synthetic Pair", floatArrayOf(0f, 1f), id = "pair") } } }
            screen(s, scale, memory, root) {
                awaitLabel("Synthetic Healthy")
                assertState("$tag list", s, other, seen)
                tap(s.ux.voiceProfilesExport)
                awaitLabel(s.ux.voiceProfilesExportReady)
                assertState("$tag export", s, other, seen)
                tap("Synthetic Healthy")
                // The fields also render a visible label, so assert the accessibility name itself.
                for (field in listOf(s.name, s.ux.role, s.ux.company, s.ux.email, s.notes, s.ux.voiceProfilesImportTitle)) {
                    compose.onNodeWithContentDescription(field).performScrollTo().assertExists()
                }
                assertState("$tag editor", s, other, seen)
                tap(s.save)
                awaitLabel(s.ux.voiceProfilesSaved)
                assertState("$tag saved", s, other, seen)
                tap(s.ux.deleteProfile)
                awaitLabel(s.ux.confirmDeleteProfileBody)
                assertState("$tag delete dialog", s, other, seen)
                tap(s.delete)
                awaitLabel(s.ux.voiceProfilesDeleted)
                assertState("$tag deleted", s, other, seen)
            }
            root.deleteRecursively()
        }

        // Unreadable profiles (one per problem the screen can name) and the removal confirmation.
        newRoot().let { root ->
            val memory = InMemorySecureStore()
            withProfile(root, memory)
            runBlocking { VoiceProfileStore(root, memory).withReady { upsert("Synthetic Corrupt", floatArrayOf(0f, 1f), id = "corrupt") } }
            val dir = root.resolve("voices/profiles")
            // Missing embedding, missing metadata, garbage metadata, wrong-length embedding, and a tampered ciphertext.
            dir.resolve("orphan.json").writeBytes("""{"id":"orphan","displayName":"Synthetic Orphan","dim":2,"encrypted":true}""".encodeToByteArray())
            dir.resolve("nometa.f32").writeBytes(ByteArray(8))
            dir.resolve("badmeta.json").writeBytes("not json".encodeToByteArray())
            dir.resolve("badembed.json").writeBytes("""{"id":"badembed","displayName":"Synthetic BadEmbed","dim":2,"encrypted":false}""".encodeToByteArray())
            dir.resolve("badembed.f32").writeBytes(ByteArray(12))
            val enc = dir.resolve("corrupt.enc")
            enc.writeBytes(enc.readBytes().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0x55).toByte() })
            screen(s, scale, memory, root) {
                awaitLabel(s.ux.voiceProfilesUnreadableTitle)
                for (msg in listOf(
                    s.ux.voiceProfilesMissingEmbedding, s.ux.voiceProfilesMissingMetadata, s.ux.voiceProfilesInvalidMetadata,
                    s.ux.voiceProfilesInvalidEmbedding, s.ux.voiceProfilesCannotDecrypt,
                )) awaitLabel(msg)
                assertState("$tag unreadable", s, other, seen)
                tap(s.ux.voiceProfilesRemoveFiles)
                awaitLabel(s.ux.confirmDeleteTitle)
                assertState("$tag remove files dialog", s, other, seen)
            }
            root.deleteRecursively()
        }

        // Import of a real export (a profile moved to an empty library under the same key).
        newRoot().let { source ->
            val memory = InMemorySecureStore()
            withProfile(source, memory)
            val export = runBlocking { VoiceProfileStore(source, memory).withReady { exportEncrypted() } }
            newRoot().let { root ->
                screen(s, scale, memory, root) {
                    awaitLabel(s.ux.voiceProfilesEmpty)
                    compose.onNodeWithContentDescription(s.ux.voiceProfilesImportTitle).performScrollTo().performTextInput("not a backup")
                    tap(s.ux.voiceProfilesImport)
                    awaitLabel(s.ux.voiceProfilesImportInvalid)
                    assertState("$tag import invalid", s, other, seen)
                }
                root.deleteRecursively()
            }
            // The same backup opened under a different key.
            newRoot().let { root ->
                screen(s, scale, InMemorySecureStore(), root) {
                    awaitLabel(s.ux.voiceProfilesEmpty)
                    compose.onNodeWithContentDescription(s.ux.voiceProfilesImportTitle).performScrollTo().performTextInput(export)
                    tap(s.ux.voiceProfilesImport)
                    awaitLabel(s.ux.voiceProfilesImportWrongKey)
                    assertState("$tag import wrong key", s, other, seen)
                }
                root.deleteRecursively()
            }
            newRoot().let { root ->
                screen(s, scale, memory, root) {
                    awaitLabel(s.ux.voiceProfilesEmpty)
                    compose.onNodeWithContentDescription(s.ux.voiceProfilesImportTitle).performScrollTo().performTextInput(export)
                    tap(s.ux.voiceProfilesImport)
                    awaitLabel(s.ux.voiceProfilesImportedOne)
                    assertState("$tag imported", s, other, seen)
                }
                root.deleteRecursively()
            }
            source.deleteRecursively()
        }

        // Lost key, then a different failure, then a wipe whose reload fails.
        newRoot().let { root ->
            val memory = InMemorySecureStore()
            withProfile(root, memory)
            runBlocking { memory.putString(VoiceProfileStore.PROFILE_KEY_SECURE, Base64.encode(ByteArray(32) { 37 })) }
            val flaky = FlakyReads(memory)
            screen(s, scale, flaky, root) {
                awaitLabel(s.ux.voiceProfilesKeyUnavailable)
                // A different failure after the lost key hides the recovery button: wiping fixes only a lost key.
                flaky.failReads = true
                tap(s.ux.retryProcessing)
                awaitLabel(s.ux.secureStorageUnavailable)
                compose.onAllNodesWithContentDescription(s.ux.voiceProfilesWipe).assertAll(isNotEnabled())
                // Back to the lost key, wipe, and let the reload after the wipe fail.
                flaky.failReads = false
                tap(s.ux.retryProcessing)
                awaitLabel(s.ux.voiceProfilesKeyUnavailable)
                tap(s.ux.voiceProfilesWipe)
                awaitLabel(s.ux.confirmWipeProfilesBody)
                flaky.failReads = true
                tap(s.confirm)
                awaitLabel(s.ux.voiceProfilesWiped)
                val texts = nodes().map { it.first }
                assertTrue(texts.none { it.contains(s.ux.secureStorageUnavailable) || it.contains(s.ux.voiceProfilesKeyUnavailable) }, "$tag: a stale 'data kept' message after the wipe: $texts")
                compose.onNodeWithContentDescription(s.ux.retryProcessing).performScrollTo().assertExists()
            }
            root.deleteRecursively()
        }

        // Wrong key, then storage unavailable.
        newRoot().let { root ->
            val memory = InMemorySecureStore()
            withProfile(root, memory)
            runBlocking { memory.putString(VoiceProfileStore.PROFILE_KEY_SECURE, Base64.encode(ByteArray(32) { 37 })) }
            screen(s, scale, memory, root) {
                awaitLabel(s.ux.voiceProfilesKeyUnavailable)
                compose.onNodeWithContentDescription(s.ux.retryProcessing).performScrollTo().assertExists()
                assertState("$tag wrong key", s, other, seen)
                // The recovery path: wiping needs no key and asks first.
                tap(s.ux.voiceProfilesWipe)
                awaitLabel(s.ux.confirmWipeProfilesBody)
                tap(s.confirm)
                awaitLabel(s.ux.voiceProfilesWiped)
                assertState("$tag wiped after a lost key", s, other, seen)
            }
            val unavailable = object : SecureStore by memory {
                override suspend fun getString(key: String): String? {
                    if (key == VoiceProfileStore.PROFILE_KEY_SECURE) throw SecureStoreUnavailableException(ProviderException("synthetic"))
                    return memory.getString(key)
                }
            }
            screen(s, scale, unavailable, root) {
                awaitLabel(s.ux.secureStorageUnavailable)
                // A transient failure keeps the data: wiping is not offered (the always-present wipe button is disabled).
                compose.onAllNodesWithContentDescription(s.ux.voiceProfilesWipe).assertAll(isNotEnabled())
                compose.onNodeWithContentDescription(s.ux.retryProcessing).performScrollTo().assertExists()
                assertState("$tag storage unavailable", s, other, seen)
            }
            root.deleteRecursively()
        }

        // Coverage guard: every voiceProfiles* key was shown, or is listed (with a reason) as unreachable here.
        val all = voiceKeys(s).keys
        assertTrue(all.size >= 30, "$tag: reflection found only ${all.size} voiceProfiles keys")
        val uncovered = all - seen - UNREACHED.keys
        assertTrue(uncovered.isEmpty(), "$tag: voiceProfiles strings not exercised and not listed in UNREACHED: $uncovered")
        val stale = UNREACHED.keys.filter { it in seen }
        assertTrue(stale.isEmpty(), "$tag: listed as UNREACHED but shown: $stale")
    }
}
