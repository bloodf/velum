package dev.velum.app.importing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.velum.ai.local.ModelStoreDir
import dev.velum.ai.pipeline.RecordingJobScheduler
import dev.velum.app.di.AppContainer
import dev.velum.core.data.DriverFactory
import dev.velum.core.security.InMemorySecureStore
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** #329: recreating the screen (rotation, theme, locale) cancels its coroutines; a share being imported must still land. */
@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class, sdk = [34])
class DetachedShareImportTest {
    @Test fun cancelling_the_collector_mid_normalize_still_creates_the_recording() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("velum.db")
        val root = ModelStoreDir(Files.createTempDirectory("velum-detached").toString())
        val container = AppContainer(
            InMemorySecureStore(), DriverFactory(context), modelStoreRoot = root,
            recordingJobSchedulerFactory = { _, _ -> RecordingJobScheduler { _, _, _, _ -> } },
        )
        val dir = File(context.cacheDir, "detached-fixtures").apply { deleteRecursively(); mkdirs() }
        try {
            val wav = silentWav(File(dir, "share.wav"))
            val started = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val pipeline = AudioImportPipeline(container, normalize = { path ->
                started.complete(Unit)
                gate.await()
                dev.velum.app.platform.AudioNormalizer().normalizeToWav(path)
            })
            runBlocking {
                val collector = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { pipeline.importSharedDetached(wav.path) }
                withTimeout(30_000) { started.await() }
                collector.cancel() // the screen is recreated while normalize is running
                collector.join()
                gate.complete(Unit)
                var count = 0L
                withTimeout(60_000) {
                    while (count == 0L) {
                        count = container.repository.storageUsage().recordingCount
                        if (count == 0L) kotlinx.coroutines.delay(100)
                    }
                }
                assertEquals(1L, count, "the import must finish after the collector is cancelled")
            }
        } finally {
            container.databaseHandle.driver.close()
            root.deleteRecursively()
            dir.deleteRecursively()
        }
    }

    private fun silentWav(file: File): File {
        val samples = 16_000 / 5
        val buffer = java.nio.ByteBuffer.allocate(44 + samples * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(16_000).putInt(32_000).putShort(2).putShort(16)
        buffer.put("data".toByteArray()).putInt(samples * 2)
        repeat(samples) { buffer.putShort(0) }
        file.writeBytes(buffer.array())
        return file
    }
}
