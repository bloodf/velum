package dev.velum.app.importing

import dev.velum.ai.local.ModelStoreDir
import dev.velum.ai.pipeline.EnqueueResult
import dev.velum.ai.pipeline.RecordingJobScheduler
import dev.velum.app.di.AppContainer
import dev.velum.app.platform.isAppOwnedAudioPath
import dev.velum.app.platform.recordingsDirectoryPath
import dev.velum.core.data.DriverFactory
import dev.velum.core.security.InMemorySecureStore
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking

class DesktopImportDeletionTest {
    @Test
    fun imported_audio_is_owned_and_permanent_deletion_preserves_source() = runBlocking {
        val root = Files.createTempDirectory("velum-desktop-import").toFile()
        val oldHome = System.getProperty("user.home")
        var scope: CoroutineScope? = null
        var container: AppContainer? = null
        try {
            System.setProperty("user.home", File(root, "home").absolutePath)
            val source = File(root, "external/source.wav").apply { parentFile.mkdirs(); writeBytes(syntheticWav()) }
            val original = source.readBytes()
            val app = AppContainer(
                InMemorySecureStore(), DriverFactory(), modelStoreRoot = ModelStoreDir(File(root, "models").path),
                recordingJobSchedulerFactory = { _, appScope ->
                    scope = appScope
                    RecordingJobScheduler { _, _, _, _ -> EnqueueResult.STARTED_OR_REPLACED }
                },
            )
            container = app
            val created = assertIs<ImportResult.Created>(AudioImportPipeline(app).importFile(source.absolutePath))
            val generated = File(created.wavPath)
            assertEquals(generated.absolutePath, app.repository.getRecording(created.recordingId)?.local_path)
            assertTrue(generated.isFile)
            assertTrue(app.deleteRecordingForever(created.recordingId))
            assertFalse(generated.exists(), "generated audio survives permanent deletion")
            assertNull(app.repository.getRecording(created.recordingId))
            assertContentEquals(original, source.readBytes())
            assertTrue(isAppOwnedAudioPath(created.wavPath))
            assertEquals(File(recordingsDirectoryPath()).canonicalFile, generated.canonicalFile.parentFile)
            assertFalse(source.canonicalFile == generated.canonicalFile)
        } finally {
            scope?.coroutineContext?.job?.cancelAndJoin()
            container?.databaseHandle?.driver?.close()
            if (oldHome == null) System.clearProperty("user.home") else System.setProperty("user.home", oldHome)
            root.deleteRecursively()
        }
    }

    private fun syntheticWav(): ByteArray = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(40); put("WAVEfmt ".toByteArray()); putInt(16)
        putShort(1); putShort(1); putInt(16_000); putInt(32_000); putShort(2); putShort(16)
        put("data".toByteArray()); putInt(4); putInt(0)
    }.array()
}
