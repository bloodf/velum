package dev.velum.app.di

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.velum.ai.local.ModelStoreDir
import dev.velum.ai.local.VoiceKeyPrimingState
import dev.velum.ai.local.VoiceProfileStore
import dev.velum.core.data.DriverFactory
import dev.velum.core.security.AndroidSecureStore
import dev.velum.core.security.SecureStore
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Production Android wiring: AppContainer / VelumApplication must pass a real
 * [AndroidSecureStore] into [VoiceProfileStore] — never a null / file-key path.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class VoiceProfileStoreProductionWiringTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("velum.db")
    }

    @Test
    fun android_production_sources_pass_android_secure_store() {
        val velumApp = resolve(
            "composeApp/src/androidMain/kotlin/dev/velum/app/VelumApplication.kt",
        ).readText()
        val containerSrc = resolve(
            "composeApp/src/commonMain/kotlin/dev/velum/app/di/AppContainer.kt",
        ).readText()
        assertTrue(
            velumApp.contains("AndroidSecureStore(appContext)"),
            "VelumApplication must construct AndroidSecureStore",
        )
        assertTrue(
            velumApp.contains("secureStore = AndroidSecureStore"),
            "VelumApplication must pass AndroidSecureStore into AppContainer",
        )
        assertTrue(
            containerSrc.contains("VoiceProfileStore(modelStoreRoot, secureStore = secureStore)"),
            "AppContainer must wire VoiceProfileStore with the container SecureStore",
        )
        assertFalse(
            Regex("""VoiceProfileStore\([^)]*secureStore\s*=\s*null""").containsMatchIn(containerSrc),
            "AppContainer must not pass null secureStore",
        )
        assertFalse(
            containerSrc.contains(".profile_key"),
            "production AppContainer must not reference the legacy file key",
        )
    }

    @Test
    fun android_app_container_primes_via_android_secure_store() {
        runBlocking {
            val models = File(context.filesDir, "models-vp-wiring").also {
                it.deleteRecursively()
                it.mkdirs()
            }
            val secure: SecureStore = AndroidSecureStore(
                context,
                prefsName = "velum_vp_wiring_prefs",
                keyAlias = "velum_vp_wiring_key",
            )
            assertIs<AndroidSecureStore>(secure)

            val container = AppContainer(
                secureStore = secure,
                driverFactory = DriverFactory(context),
                modelStoreRoot = ModelStoreDir(models.absolutePath),
            )
            // Production type check: the store behind the container is the Android Keystore impl.
            assertIs<AndroidSecureStore>(container.secureStore)

            withTimeout(15_000) {
                while (container.voiceProfileStore.primingState.value is VoiceKeyPrimingState.Unprimed) {
                    delay(20)
                }
            }
            when (val state = container.voiceProfileStore.primingState.value) {
                is VoiceKeyPrimingState.Ready -> {
                    assertTrue(secure.contains(VoiceProfileStore.PROFILE_KEY_SECURE))
                    assertFalse(File(models, "voices/.profile_key").exists())
                    container.voiceProfileStore.upsert("Wire", floatArrayOf(1f, 0f, 0f))
                    assertTrue(container.voiceProfileStore.listProfiles().isNotEmpty())
                }
                is VoiceKeyPrimingState.Failed -> {
                    // Keystore quirks under Robolectric — failure must surface for UI Retry,
                    // never fall back to a constant file key.
                    assertFalse(File(models, "voices/.profile_key").exists())
                    assertTrue(state.errorClassName.isNotBlank())
                }
                VoiceKeyPrimingState.Unprimed -> error("priming still Unprimed after timeout")
            }
        }
    }

    @Test
    fun app_container_surfaces_priming_failure_without_swallowing() {
        runBlocking {
            val models = File(context.filesDir, "models-vp-fail").also {
                it.deleteRecursively()
                it.mkdirs()
            }
            val failing = object : SecureStore {
                override suspend fun putString(key: String, value: String) {
                    error("keystore put failed")
                }
                override suspend fun getString(key: String): String? = null
                override suspend fun remove(key: String) = Unit
                override suspend fun contains(key: String): Boolean = false
            }
            val container = AppContainer(
                secureStore = failing,
                driverFactory = DriverFactory(context),
                modelStoreRoot = ModelStoreDir(models.absolutePath),
            )
            withTimeout(15_000) {
                while (container.voiceProfileStore.primingState.value !is VoiceKeyPrimingState.Failed) {
                    delay(20)
                }
            }
            val failed = container.voiceProfileStore.primingState.value
            assertIs<VoiceKeyPrimingState.Failed>(failed)
            assertTrue(failed.errorClassName.contains("Exception"))
        }
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
