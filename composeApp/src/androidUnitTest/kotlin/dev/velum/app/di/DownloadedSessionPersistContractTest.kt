package dev.velum.app.di

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.velum.ai.audio.DownloadedSessionAudioPersist
import dev.velum.ai.audio.PcmWav
import dev.velum.ai.local.ModelStoreDir
import dev.velum.app.platform.installRecordingFilesDir
import dev.velum.app.platform.recordingsDirectoryPath
import dev.velum.app.ui.DownloadedSessionAudio
import dev.velum.core.data.DriverFactory
import dev.velum.core.security.InMemorySecureStore
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Contract: [AppContainer.persistDownloadedSession] (not just the helper) materializes
 * every decrypted body shape as ogg|wav via temp+rename — never `.bin`.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DownloadedSessionPersistContractTest {
    private lateinit var filesRoot: File
    private lateinit var container: AppContainer

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("velum.db")
        filesRoot = File(context.cacheDir, "velum-persist-contract").apply {
            deleteRecursively()
            mkdirs()
        }
        installRecordingFilesDir(filesRoot.absolutePath)
        // Isolated model root so this suite does not race screenshot VoiceProfileStore priming.
        val models = File(filesRoot, "models").apply { mkdirs() }
        container = AppContainer(
            secureStore = InMemorySecureStore(),
            driverFactory = DriverFactory(context),
            modelStoreRoot = ModelStoreDir(models.absolutePath),
        )
        runBlocking { container.voiceProfileStore.ensureKey() }
    }

    @Test
    fun persistDownloadedSession_ogg_writesOggAndCleansTemp() = runBlocking {
        val ogg = byteArrayOf(
            'O'.code.toByte(), 'g'.code.toByte(), 'g'.code.toByte(), 'S'.code.toByte(),
            9, 9, 9, 9,
        )
        val id = container.persistDownloadedSession(session(ogg))
        val rec = container.repository.getRecording(id)
        assertNotNull(rec)
        val path = rec.local_path
        assertNotNull(path)
        assertTrue(path.endsWith(".ogg"), path)
        assertTrue(File(path).exists())
        assertTrue(File(path).readBytes().contentEquals(ogg))
        assertDownloadTempGone(path)
        assertEquals("ogg", DownloadedSessionAudioPersist.detectExtension(ogg))
    }

    @Test
    fun persistDownloadedSession_wav_writesWavAndCleansTemp() = runBlocking {
        val wav = PcmWav.encode(ByteArray(16) { 2 }, sampleRateHz = 16_000)
        val id = container.persistDownloadedSession(session(wav))
        val path = container.repository.getRecording(id)?.local_path
        assertNotNull(path)
        assertTrue(path.endsWith(".wav"), path)
        assertTrue(File(path).readBytes().contentEquals(wav))
        assertDownloadTempGone(path)
    }

    @Test
    fun persistDownloadedSession_rawPcm_wrapsWavNeverBin() = runBlocking {
        val pcm = ByteArray(100) { it.toByte() }
        val id = container.persistDownloadedSession(session(pcm))
        val rec = container.repository.getRecording(id)
        assertNotNull(rec)
        val path = rec.local_path
        assertNotNull(path)
        assertTrue(path.endsWith(".wav"), "must not be .bin: $path")
        assertFalse(path.endsWith(".bin"))
        val bytes = File(path).readBytes()
        val expected = PcmWav.encode(
            pcm,
            sampleRateHz = DownloadedSessionAudioPersist.DEFAULT_SAMPLE_RATE_HZ,
            channels = DownloadedSessionAudioPersist.DEFAULT_CHANNELS,
        )
        // Whole WAV (header + body) must match PcmWav.encode byte-for-byte.
        assertTrue(bytes.contentEquals(expected))
        assertDownloadTempGone(path)
        // Landed under the installed recordings dir.
        assertTrue(path.startsWith(recordingsDirectoryPath()))
    }

    private fun assertDownloadTempGone(destPath: String) {
        val dest = File(destPath)
        val tmp = File(
            File(dest.parentFile, DownloadedSessionAudioPersist.DOWNLOAD_TEMP_DIR_NAME),
            dest.name + DownloadedSessionAudioPersist.TEMP_SUFFIX,
        )
        assertFalse(tmp.exists(), "download temp must be gone: ${tmp.absolutePath}")
        assertFalse(File(destPath + DownloadedSessionAudioPersist.TEMP_SUFFIX).exists())
    }

    private fun session(bytes: ByteArray) = DownloadedSessionAudio(
        sessionId = 1L,
        deviceSerial = "test",
        startedAtEpochSeconds = 1_700_000_000L,
        sizeBytes = bytes.size.toLong(),
        decryptedBytes = bytes,
        sampleRateHz = DownloadedSessionAudioPersist.DEFAULT_SAMPLE_RATE_HZ,
        channels = DownloadedSessionAudioPersist.DEFAULT_CHANNELS,
    )
}
