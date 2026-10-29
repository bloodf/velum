package dev.velum.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.velum.ai.audio.DownloadedSessionAudioPersist
import dev.velum.app.platform.publishContainerAfterStaleDownloadCleanup
import dev.velum.core.data.DriverFactory
import dev.velum.core.data.VelumRepository
import dev.velum.core.data.createDatabase
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * #119: VelumApplication must wire [publishContainerAfterStaleDownloadCleanup] so cleanup
 * finishes before `ready.set(true)` / `containerDeferred.complete`. Helper-only tests from
 * #86 cannot catch an app that skips the helper and inlines publish-then-cleanup.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class VelumApplicationStartupWiringTest {
    private lateinit var recordings: File
    private lateinit var downloadTmp: File
    private lateinit var repository: VelumRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("velum.db")
        recordings = File(context.cacheDir, "velum-app-startup-wiring").apply {
            deleteRecursively()
            mkdirs()
        }
        downloadTmp = File(recordings, DownloadedSessionAudioPersist.DOWNLOAD_TEMP_DIR_NAME).apply {
            mkdirs()
        }
        repository = VelumRepository(createDatabase(DriverFactory(context)))
    }

    @Test
    fun velumApplication_callsPublishHelper_withReadyOnlyInsidePublish() {
        val src = velumApplicationSource()
        val successPath = src
            .substringAfter("AiRuntimeHolder.install(")
            .substringBefore("} catch (t: Throwable)")

        assertTrue(
            successPath.contains("publishContainerAfterStaleDownloadCleanup("),
            "VelumApplication success path must call publishContainerAfterStaleDownloadCleanup",
        )

        val helperIdx = successPath.indexOf("publishContainerAfterStaleDownloadCleanup(")
        val readyIdx = successPath.indexOf("ready.set(true)")
        val completeIdx = successPath.indexOf("containerDeferred.complete(")
        val publishIdx = successPath.indexOf("publish =", helperIdx)

        assertTrue(helperIdx >= 0)
        assertTrue(publishIdx > helperIdx, "publish lambda must be an argument to the helper")
        assertTrue(
            readyIdx > publishIdx,
            "ready.set(true) must run inside publish=, not before cleanup finishes",
        )
        assertTrue(
            completeIdx > publishIdx,
            "containerDeferred.complete must run inside publish=, not before cleanup finishes",
        )
        assertFalse(
            successPath.substring(0, helperIdx).contains("ready.set(true)"),
            "success path must not publish readiness before startup cleanup",
        )
        assertFalse(
            successPath.substring(0, helperIdx).contains("containerDeferred.complete("),
            "success path must not complete the container deferred before startup cleanup",
        )
    }

    @Test
    fun publishContainerAfterStaleDownloadCleanup_wipesStaleBeforePublish() = runBlocking {
        val stale = File(downloadTmp, "stale.ogg${DownloadedSessionAudioPersist.TEMP_SUFFIX}")
        stale.writeBytes(byteArrayOf(1, 2, 3))
        val legacy = File(recordings, "x.ogg.partial")
        legacy.writeBytes(byteArrayOf(4, 5, 6))

        var stalePresentAtPublish = true
        var legacyPresentAtPublish = true
        publishContainerAfterStaleDownloadCleanup(
            recordingsDirPath = recordings.absolutePath,
            repository = repository,
            onMicRecovered = {},
            publish = {
                // Publish-then-cleanup (or dropped cleanup) leaves debris visible here.
                stalePresentAtPublish = stale.exists()
                legacyPresentAtPublish = legacy.exists()
            },
        )

        assertFalse(stalePresentAtPublish, "stale .download-tmp must be gone before publish")
        assertFalse(legacyPresentAtPublish, "legacy download partial must be gone before publish")
        assertFalse(stale.exists())
        assertFalse(legacy.exists())
    }

    @Test
    fun publishContainerAfterStaleDownloadCleanup_liveTempAfterPublishSurvives() = runBlocking {
        File(downloadTmp, "stale.ogg${DownloadedSessionAudioPersist.TEMP_SUFFIX}")
            .writeBytes(byteArrayOf(1, 2, 3))

        val published = CompletableDeferred<Unit>()
        val liveTempWritten = CompletableDeferred<Unit>()
        val live = File(
            downloadTmp,
            "live-session.ogg${DownloadedSessionAudioPersist.TEMP_SUFFIX}",
        )
        val liveBytes = byteArrayOf(
            'O'.code.toByte(), 'g'.code.toByte(), 'g'.code.toByte(), 'S'.code.toByte(), 9,
        )

        val download = async {
            published.await()
            live.parentFile?.mkdirs()
            live.writeBytes(liveBytes)
            liveTempWritten.complete(Unit)
        }

        publishContainerAfterStaleDownloadCleanup(
            recordingsDirPath = recordings.absolutePath,
            repository = repository,
            onMicRecovered = {},
            publish = {
                published.complete(Unit)
            },
        )
        download.await()
        liveTempWritten.await()

        assertFalse(
            File(downloadTmp, "stale.ogg${DownloadedSessionAudioPersist.TEMP_SUFFIX}").exists(),
        )
        assertTrue(
            live.exists(),
            "live download temp written after publish must survive startup cleanup",
        )
        assertTrue(live.readBytes().contentEquals(liveBytes))
    }

    private fun velumApplicationSource(): String {
        val candidates = listOf(
            File("src/androidMain/kotlin/dev/velum/app/VelumApplication.kt"),
            File("composeApp/src/androidMain/kotlin/dev/velum/app/VelumApplication.kt"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("VelumApplication.kt not found from ${File(".").absolutePath}")
        return file.readText()
    }
}
