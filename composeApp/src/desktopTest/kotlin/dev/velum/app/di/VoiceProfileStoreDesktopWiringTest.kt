package dev.velum.app.di

import dev.velum.ai.local.ModelStoreDir
import dev.velum.ai.local.VoiceKeyPrimingState
import dev.velum.ai.local.VoiceProfileStore
import dev.velum.core.data.DriverFactory
import dev.velum.core.security.DesktopSecureStore
import dev.velum.core.security.SecureStore
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Production desktop wiring: Main.kt must pass a real [DesktopSecureStore] into
 * [AppContainer] / [VoiceProfileStore] — never a null / file-key path.
 */
class VoiceProfileStoreDesktopWiringTest {

    @Test
    fun desktop_production_sources_pass_desktop_secure_store() {
        val main = resolve(
            "composeApp/src/desktopMain/kotlin/dev/velum/app/Main.kt",
        ).readText()
        val container = resolve(
            "composeApp/src/commonMain/kotlin/dev/velum/app/di/AppContainer.kt",
        ).readText()
        assertTrue(
            main.contains("DesktopSecureStore()"),
            "desktop Main must construct DesktopSecureStore",
        )
        assertTrue(
            main.contains("secureStore = DesktopSecureStore"),
            "desktop Main must pass DesktopSecureStore into AppContainer",
        )
        assertTrue(
            container.contains("VoiceProfileStore(modelStoreRoot, secureStore = secureStore)"),
            "AppContainer must wire VoiceProfileStore with the container SecureStore",
        )
        assertFalse(
            Regex("""VoiceProfileStore\([^)]*secureStore\s*=\s*null""").containsMatchIn(container),
            "AppContainer must not pass null secureStore",
        )
    }

    @Test
    fun desktop_app_container_primes_via_desktop_secure_store() = runBlocking {
        val secureFile = Files.createTempDirectory("velum-desktop-secure").resolve("secure.properties")
        val models = Files.createTempDirectory("velum-desktop-models").toFile()
        val secure: SecureStore = DesktopSecureStore(secureFile)
        assertIs<DesktopSecureStore>(secure)

        val container = AppContainer(
            secureStore = secure,
            driverFactory = DriverFactory(),
            modelStoreRoot = ModelStoreDir(models.absolutePath),
        )
        withTimeout(10_000) {
            while (container.voiceProfileStore.primingState.value is VoiceKeyPrimingState.Unprimed) {
                delay(20)
            }
        }
        assertIs<VoiceKeyPrimingState.Ready>(container.voiceProfileStore.primingState.value)
        assertTrue(secure.contains(VoiceProfileStore.PROFILE_KEY_SECURE))
        assertFalse(File(models, "voices/.profile_key").exists())
        container.voiceProfileStore.upsert("Desktop", floatArrayOf(1f, 0f, 0f))
        assertTrue(container.voiceProfileStore.listProfiles().isNotEmpty())
    }

    private fun resolve(relative: String): File {
        val candidates = listOf(
            File(relative),
            File("../$relative"),
            File("../../$relative"),
            File("/workspace/$relative"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("missing $relative (cwd=${File(".").absolutePath})")
    }
}
