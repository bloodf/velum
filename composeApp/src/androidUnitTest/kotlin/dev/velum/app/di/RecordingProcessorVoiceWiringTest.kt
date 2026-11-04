package dev.velum.app.di

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.velum.ai.local.ModelStoreDir
import dev.velum.ai.pipeline.RecordingProcessor
import dev.velum.core.data.DriverFactory
import dev.velum.core.security.InMemorySecureStore
import java.nio.file.Files
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * #399: the processor replaces the speaker cache together with the speaker rows only if it has the voice store. Its
 * parameter defaults to null, so dropping the wiring in the container would silently stop caching (and keep old caches).
 */
@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class, sdk = [34])
class RecordingProcessorVoiceWiringTest {
    @Test
    fun theContainerHandsItsVoiceStoreToTheRecordingProcessor() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("velum.db")
        val root = ModelStoreDir(Files.createTempDirectory("velum-voice-wiring").toString())
        val container = AppContainer(InMemorySecureStore(), DriverFactory(context), modelStoreRoot = root)
        // Nothing is closed or deleted afterwards: the container's startup coroutines (sweeps, the AutoFlow runner)
        // may still be using the driver and the directory, and the check reads one field.
        val field = RecordingProcessor::class.java.getDeclaredField("voiceProfiles").apply { isAccessible = true }
        val wired = field.get(container.recordingProcessor)
        assertNotNull(wired, "RecordingProcessor has no voice profile store: the speaker cache is never replaced")
        assertSame(container.voiceProfileStore, wired)
    }
}
