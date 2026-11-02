package dev.velum.app.di

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.core.app.ApplicationProvider
import dev.velum.ai.local.ModelStoreDir
import dev.velum.app.i18n.*
import dev.velum.app.ui.settings.ImportDeviceKeysScreen
import dev.velum.app.ui.theme.VelumTheme
import dev.velum.core.data.DriverFactory
import dev.velum.core.security.*
import dev.velum.core.settings.SettingsLoadState
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class DeviceKeyImportStorageUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun malformed_json_has_redacted_english_error_and_masked_identity_fields() = exerciseRedaction(En, json = true, unexpected = false)
    @Test fun malformed_json_has_redacted_portuguese_error_and_masked_identity_fields() = exerciseRedaction(PtBr, json = true, unexpected = false)
    @Test fun malformed_pasted_signature_has_redacted_english_error_and_preserves_keys() = exerciseRedaction(En, json = false, unexpected = false)
    @Test fun malformed_pasted_signature_has_redacted_portuguese_error_and_preserves_keys() = exerciseRedaction(PtBr, json = false, unexpected = false)
    @Test fun unexpected_json_failure_never_echoes_identity_or_raw_exception() = exerciseRedaction(En, json = true, unexpected = true)
    @Test fun unexpected_pasted_failure_never_echoes_identity_or_raw_exception() = exerciseRedaction(PtBr, json = false, unexpected = true)

    private fun exerciseRedaction(strings: Strings, json: Boolean, unexpected: Boolean) = fixture { f ->
        val logStart = ShadowLog.getLogs().size
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent { CompositionLocalProvider(LocalStrings provides strings) { VelumTheme(darkTheme = false) { ImportDeviceKeysScreen(f.container, onBack = {}) } } }
            compose.waitUntil(10_000) { f.container.settingsRepository.loadState.value == SettingsLoadState.Ready }
            val marker = "SYNTHETIC-IDENTITY-SECRET"
            if (json) {
                val raw = if (unexpected) f.json else """{"version":1,"source":"synthetic","device":{"serial":"$marker","snSignatureBase64":{},"bindToken":"$marker","privateKeyPem":"$marker"}}"""
                field(strings.importKeys.fromPlaudJsonPaste).performTextInput(raw)
            } else {
                field(strings.importKeys.serial).performTextInput(marker)
                field(strings.importKeys.snSignatureB64).performTextInput(if (unexpected) f.signature else "#$marker")
                field(strings.importKeys.bindToken).performTextInput(marker)
                field(strings.importKeys.rsaPublicPemOptional).performTextInput(marker)
                field(strings.importKeys.rsaPrivatePemOptional).performTextInput(marker)
            }
            // Password semantics and transformed display apply to every identity input,
            // including a JSON draft. Editable values remain available only to editing APIs.
            for (label in listOf(strings.importKeys.fromPlaudJsonPaste, strings.importKeys.serial, strings.importKeys.snSignatureB64, strings.importKeys.bindToken, strings.importKeys.rsaPublicPemOptional, strings.importKeys.rsaPrivatePemOptional)) {
                field(label).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
            }
            val before = f.store.values.toMap()
            if (unexpected) f.store.fault = IllegalStateException(marker)
            val label = if (json) strings.importKeys.fromPlaudJsonApply else strings.save
            compose.onNodeWithContentDescription(label).performScrollTo().performClick()
            val expected = if (json && !unexpected) strings.importKeys.invalidJson else strings.importKeys.importFailed
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("identity-import-error").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("identity-import-error").performScrollTo().assertIsDisplayed().assertTextEquals(expected)
            compose.onNodeWithTag("identity-import-error").assert(hasText(marker, substring = true).not())
            compose.onAllNodesWithText(strings.importKeys.saved).assertCountEquals(0)
            compose.onAllNodesWithText(strings.importKeys.fromPlaudJsonOk).assertCountEquals(0)
            assertEquals(before, f.store.values)
            if (!unexpected) assertEquals(0, f.store.attempts.get())
            val capturedLogs = ShadowLog.getLogs().drop(logStart).joinToString("\n") { it.msg + it.throwable?.stackTraceToString().orEmpty() }
            assertFalse(capturedLogs.contains(marker))
            assertFalse(capturedLogs.contains(f.signature))
            assertFalse(capturedLogs.contains("synthetic-new-bind"))
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun pasted_fields_surface_localized_vault_failure_preserve_entries_and_retry_without_false_success() = exerciseUi(PtBr, json = false)
    @Test fun json_import_surfaces_localized_vault_failure_preserves_draft_and_retries_without_false_success() = exerciseUi(En, json = true)

    private fun exerciseUi(strings: Strings, json: Boolean) = fixture { f ->
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent { CompositionLocalProvider(LocalStrings provides strings) { VelumTheme(darkTheme = false) { ImportDeviceKeysScreen(f.container, onBack = {}) } } }
            compose.waitUntil(10_000) { f.container.settingsRepository.loadState.value == SettingsLoadState.Ready }
            if (json) field(strings.importKeys.fromPlaudJsonPaste).performTextInput(f.json)
            else {
                field(strings.importKeys.serial).performTextInput("SYNTHETIC-NEW")
                field(strings.importKeys.snSignatureB64).performTextInput(f.signature)
                field(strings.importKeys.bindToken).performTextInput("synthetic-new-bind")
            }
            val before = f.store.values.toMap()
            f.store.fault = SecureStoreUnavailableException(IllegalStateException("synthetic raw input must not be shown"))
            val label = if (json) strings.importKeys.fromPlaudJsonApply else strings.save
            compose.onNodeWithContentDescription(label).performScrollTo().performClick()
            compose.waitUntil(10_000) { f.store.attempts.get() > 0 }
            compose.runOnIdle {
                assertEquals(SettingsLoadState.Unavailable, f.container.settingsRepository.loadState.value)
                assertEquals("storage_unavailable", f.container.settingsViewModel.ui.value.error)
                assertEquals(before, f.store.values)
            }
            compose.onNodeWithText(strings.ux.secureStorageUnavailable).assertExists()
            compose.onAllNodesWithText("synthetic raw input", substring = true).assertCountEquals(0)
            compose.onAllNodesWithText(strings.importKeys.saved).assertCountEquals(0)
            compose.onAllNodesWithText(strings.importKeys.fromPlaudJsonOk).assertCountEquals(0)
            if (json) field(strings.importKeys.fromPlaudJsonPaste).assertTextContains(f.json)
            else field(strings.importKeys.serial).assertTextContains("SYNTHETIC-NEW")
            f.store.fault = null
            compose.onNodeWithContentDescription(strings.ux.retryProcessing).performScrollTo().performClick()
            compose.waitUntil(10_000) { f.container.settingsRepository.loadState.value == SettingsLoadState.Ready }
            compose.onNodeWithContentDescription(label).performScrollTo().performClick()
            val success = if (json) strings.importKeys.fromPlaudJsonOk else strings.importKeys.saved
            compose.waitUntil(10_000) { compose.onAllNodesWithText(success).fetchSemanticsNodes().isNotEmpty() }
            val loaded = runBlocking { f.container.deviceKeyImporter.loadKeys() }
            assertTrue(loaded?.serialNumber == "SYNTHETIC-NEW")
            assertTrue(loaded?.bindToken == "synthetic-new-bind")
            assertTrue(SecureKeys.DEVICE_IDENTITY_BUNDLE in f.store.values)
            for ((key,value) in before) assertTrue(f.store.values[key] == value, "Legacy or sibling entry changed")
        } finally { activity.pause().stop().destroy() }
    }
    @Test fun pasted_field_import_cancellation_propagates_through_the_shared_handler_without_mutation() = exerciseCancellation(json = false)
    @Test fun json_import_cancellation_propagates_through_the_shared_handler_without_mutation() = exerciseCancellation(json = true)
    private fun exerciseCancellation(json: Boolean) = fixture { f ->
        runBlocking {
            f.container.settingsRepository.load()
            val before = f.store.values.toMap(); val cancelled = CancellationException("Synthetic cancellation")
            f.store.fault = cancelled
            val thrown = assertFailsWith<CancellationException> {
                f.container.settingsViewModel.handleStorageFailure {
                    if (json) PlaudExportImporter.importInto(f.json, f.container.deviceKeyImporter)
                    else f.container.deviceKeyImporter.importKeys("SYNTHETIC-NEW", f.signature, "synthetic-new-bind")
                }
            }
            assertSame(cancelled, thrown); assertEquals(before, f.store.values)
            assertEquals(SettingsLoadState.Ready, f.container.settingsRepository.loadState.value)
            assertNotEquals("storage_unavailable", f.container.settingsViewModel.ui.value.error)
        }
    }
    private fun field(label: String) = compose.onNode(hasSetTextAction() and hasText(label)).performScrollTo()
    private fun fixture(block: (Fixture) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Application>(); context.deleteDatabase("velum.db")
        val f = Fixture(context)
        try { block(f) } finally { f.container.databaseHandle.driver.close(); f.root.deleteRecursively() }
    }
    private class Fixture(context: Application) {
        val root = Files.createTempDirectory("velum-import-vault-synthetic").toFile()
        val signature = Base64.getEncoder().encodeToString(ByteArray(256) { 7 })
        val json = """{"version":1,"source":"synthetic-test","device":{"serial":"SYNTHETIC-NEW","snSignatureBase64":"$signature","bindToken":"synthetic-new-bind"},"templates":[]}"""
        val store = SyntheticStore().apply {
            values[SecureKeys.DEVICE_SERIAL] = "SYNTHETIC-OLD"
            values[SecureKeys.DEVICE_SERIAL_SIGNATURE] = signature
            values[SecureKeys.DEVICE_BIND_TOKEN] = "synthetic-old-bind"
            values[SecureKeys.VOICE_PROFILE_AES_KEY] = "synthetic-kept-voice-key"
            values[SecureKeys.AI_API_KEY] = "synthetic-kept-api-key"
        }
        val container = AppContainer(store, DriverFactory(context), modelStoreRoot = ModelStoreDir(File(root, "models").path))
    }
    private class SyntheticStore : SecureStore {
        val values = linkedMapOf<String, String>()
        @Volatile var fault: Exception? = null
        val attempts = AtomicInteger()
        override suspend fun putString(key: String, value: String) {
            if (key.startsWith("device.")) { attempts.incrementAndGet(); fault?.let { throw it } }
            values[key] = value
        }
        override suspend fun getString(key: String) = values[key]
        override suspend fun contains(key: String) = key in values
        override suspend fun remove(key: String) { if (key.startsWith("device.")) fault?.let { throw it }; values.remove(key) }
    }
}
