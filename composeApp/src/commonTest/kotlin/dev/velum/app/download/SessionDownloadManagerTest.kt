package dev.velum.app.download

import dev.velum.app.platform.SessionDownloadKeepAliveGate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class SessionDownloadManagerTest {
    private fun request(sessionId: Long = 7L) = SessionDownloadRequest(
        sessionId = sessionId,
        sizeBytes = 100L,
        privateKeyPem = "unused",
        deviceSerial = null,
        startedAtEpochSeconds = sessionId,
        client = null,
    )

    @Test
    fun success_releasesKeepAliveInFinally() = runBlocking {
        var starts = 0
        var stops = 0
        val gate = SessionDownloadKeepAliveGate(
            startPlatform = { starts++ },
            stopPlatform = { stops++ },
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val manager = SessionDownloadManager(
            scope = scope,
            localeTag = { "en" },
            transfer = SessionDownloadTransfer { _, _ ->
                SessionDownloadResult(recordingId = "rec-1", sessions = emptyList())
            },
            keepAlive = gate,
        )

        manager.start(request())
        manager.join()

        assertEquals(1, starts)
        assertEquals(1, stops)
        assertFalse(gate.isActive)
        assertFalse(manager.isDownloadActive)
        assertEquals("rec-1", manager.state.value.lastDownloadedRecordingId)
        scope.cancel()
    }

    @Test
    fun failure_releasesKeepAliveInFinally() = runBlocking {
        var starts = 0
        var stops = 0
        val gate = SessionDownloadKeepAliveGate(
            startPlatform = { starts++ },
            stopPlatform = { stops++ },
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val manager = SessionDownloadManager(
            scope = scope,
            localeTag = { "en" },
            transfer = SessionDownloadTransfer { _, _ -> error("boom") },
            keepAlive = gate,
        )

        manager.start(request())
        manager.join()

        assertEquals(1, starts)
        assertEquals(1, stops)
        assertFalse(gate.isActive)
        assertNull(manager.state.value.downloadingSessionId)
        assertTrue(manager.state.value.statusMessage != null)
        scope.cancel()
    }

    @Test
    fun cancel_releasesKeepAliveInFinally_once() = runBlocking {
        var starts = 0
        var stops = 0
        val gate = SessionDownloadKeepAliveGate(
            startPlatform = { starts++ },
            stopPlatform = { stops++ },
        )
        val entered = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val manager = SessionDownloadManager(
            scope = scope,
            localeTag = { "en" },
            transfer = SessionDownloadTransfer { _, _ ->
                entered.complete(Unit)
                awaitCancellation()
            },
            keepAlive = gate,
        )

        manager.start(request())
        withTimeout(2_000) { entered.await() }
        assertTrue(gate.isActive)
        assertEquals(1, starts)

        manager.cancel()
        manager.join()

        assertEquals(1, starts)
        assertEquals(1, stops)
        assertFalse(gate.isActive)
        assertFalse(manager.isDownloadActive)
        scope.cancel()
    }

    @Test
    fun downloadSurvivesViewModelScopeCancellation() = runBlocking {
        var starts = 0
        var stops = 0
        val gate = SessionDownloadKeepAliveGate(
            startPlatform = { starts++ },
            stopPlatform = { stops++ },
        )
        val entered = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<String>()
        val appJob = SupervisorJob()
        val appScope = CoroutineScope(appJob + Dispatchers.Unconfined)
        // Simulates DeviceViewModel.viewModelScope — must NOT own the download.
        val viewModelJob = SupervisorJob()
        val viewModelScope = CoroutineScope(viewModelJob + Dispatchers.Unconfined)

        val manager = SessionDownloadManager(
            scope = appScope,
            localeTag = { "en" },
            transfer = SessionDownloadTransfer { _, _ ->
                entered.complete(Unit)
                delay(50)
                val result = SessionDownloadResult(recordingId = "survived", sessions = emptyList())
                finished.complete(result.recordingId)
                result
            },
            keepAlive = gate,
        )

        // Mirror DeviceViewModel: only observe from VM scope; start on app-owned manager.
        var mirroredProgress: Float? = null
        viewModelScope.coroutineContext[Job] // ensure scope exists
        manager.start(request())
        withTimeout(2_000) { entered.await() }

        // Task swipe / ViewModel clear
        viewModelJob.cancel()
        assertTrue(viewModelJob.isCancelled)

        // Download must keep running on the app scope.
        assertTrue(manager.isDownloadActive)
        assertTrue(gate.isActive)
        assertEquals("survived", withTimeout(2_000) { finished.await() })
        manager.join()
        assertEquals(1, starts)
        assertEquals(1, stops)
        assertEquals("survived", manager.state.value.lastDownloadedRecordingId)
        mirroredProgress = manager.state.value.downloadProgress
        assertNull(mirroredProgress)
        appJob.cancel()
    }

    @Test
    fun cancelFromNotificationHandler_cancelsOwnerJob() = runBlocking {
        var cancelInvocations = 0
        val entered = CompletableDeferred<Unit>()
        val gate = SessionDownloadKeepAliveGate(startPlatform = {}, stopPlatform = {})
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        lateinit var manager: SessionDownloadManager
        manager = SessionDownloadManager(
            scope = scope,
            localeTag = { "en" },
            transfer = SessionDownloadTransfer { _, _ ->
                entered.complete(Unit)
                awaitCancellation()
            },
            keepAlive = gate,
        )

        // Simulate DownloadProgressNotifier cancel action → owner cancel().
        val notificationCancel = {
            cancelInvocations++
            manager.cancel()
        }

        manager.start(request())
        withTimeout(2_000) { entered.await() }
        notificationCancel()
        manager.join()

        assertEquals(1, cancelInvocations)
        assertFalse(manager.isDownloadActive)
        assertFalse(gate.isActive)
        scope.cancel()
    }
}
