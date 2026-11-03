package dev.velum.app.importing

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.velum.ai.local.ModelStoreDir
import dev.velum.ai.pipeline.RecordingJobScheduler
import dev.velum.app.di.AppContainer
import dev.velum.app.platform.installRecordingFilesDir
import dev.velum.core.data.DriverFactory
import dev.velum.core.security.InMemorySecureStore
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** A cancelled import is rethrown, not reported as a failed one (#319). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AudioImportCancelTest {
    private fun container(root: File): AppContainer {
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.deleteDatabase("velum.db")
        installRecordingFilesDir(root.path)
        return AppContainer(
            InMemorySecureStore(), DriverFactory(context), modelStoreRoot = ModelStoreDir(File(root, "models").path),
            recordingJobSchedulerFactory = { _, _ -> RecordingJobScheduler { _, _, _, _ -> } },
        )
    }

    @Test
    fun aCancellationFromTheNormalizerIsRethrownAndAnOrdinaryFailureStillReportsFailed() {
        val root = Files.createTempDirectory("velum-319-pipeline").toFile()
        val c = container(root)
        try {
            val src = File(root, "clip.wav").apply { writeBytes(ByteArray(64) { 1 }) }
            val cancelling = AudioImportPipeline(c, normalize = { throw CancellationException("synthetic cancel") })
            var rethrown = false
            try {
                runBlocking { cancelling.importFile(src.absolutePath) }
            } catch (_: CancellationException) {
                rethrown = true
            }
            assertTrue(rethrown, "a cancelled import must not come back as ImportResult.Failed")

            val failing = AudioImportPipeline(c, normalize = { error("synthetic decode failure") })
            val result = runBlocking { failing.importFile(src.absolutePath) }
            assertEquals(ImportResult.Failed("synthetic decode failure"), result)
        } finally {
            c.databaseHandle.driver.close()
            root.deleteRecursively()
        }
    }
}
