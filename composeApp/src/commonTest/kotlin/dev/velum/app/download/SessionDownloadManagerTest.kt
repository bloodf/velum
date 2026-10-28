package dev.velum.app.download

import dev.velum.app.platform.SessionDownloadKeepAliveGate
import dev.velum.app.platform.SessionDownloadKeepAliveStart
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

    private fun startedGate(
        onStart: () -> Unit = {},
        onStop: () -> Unit = {},
    ) = SessionDownloadKeepAliveGate(
        startPlatform = {
            onStart()
            SessionDownloadKeepAliveStart.Started
        },
        stopPlatform = onStop,
    )

    @Test
    fun success_releasesKeepAliveInFinally() = runBlocking {
        var starts = 0
        var stops = 0
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val manager = SessionDownloadManager(
            scope = scope,
            localeTag = { "en" },
            transfer = SessionDownloadTransfer { _, _ ->
                SessionDownloadResult(recordingId = "rec-1", sessions = emptyList())
            },
            keepAlive = startedGate(onStart = { starts++ }, onStop = { stops++ }),
            installPromoteFailureHandler = {},
        )

        manager.start(request())
        manager.join()

        assertEquals(1, starts)
        assertEquals(1, stops)
        assertFalse(manager.keepAliveActive)
        assertFalse(manager.isDownloadActive)
        assertEquals("rec-1", manager.state.value.lastDownloadedRecordingId)
        scope.cancel()
    }

    @Test
    fun failure_releasesKeepAliveInFinally() = runBlocking {
        var starts = 0
        var stops = 0
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val manager = SessionDownloadManager(
            scope = scope,
            localeTag = { "en" },
            transfer = SessionDownloadTransfer { _, _ -> error("boom") },
            keepAlive = startedGate(onStart = { starts++ }, onStop = { stops++ }),
            installPromoteFailureHandler = {},
        )

        manager.start(request())
        manager.join()

        assertEquals(1, starts)
        assertEquals(1, stops)
        assertFalse(manager.keepAliveActive)
        assertNull(manager.state.value.downloadingSessionId)
        assertTrue(manager.state.value.statusMessage != null)
        scope.cancel()
    }

    @Test
    fun cancelAndJoin_stopsKeepAliveOnlyAfterJobUnwinds() = runBlocking {
        var starts = 0
        var stops = 0
        var transferFinallyAt = 0L
        var keepAliveStopAt = 0L
        val entered = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val manager = SessionDownloadManager(
            scope = scope,
            localeTag = { "en" },
            transfer = SessionDownloadTransfer { _, _ ->
                entered.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    transferFinallyAt = System.nanoTime()
                }
            },
            keepAlive = startedGate(
                onStart = { starts++ },
                onStop = {
                    keepAliveStopAt = System.nanoTime()
                    stops++
                },
            ),
            installPromoteFailureHandler = {},
        )

        manager.start(request())
        withTimeout(2_000) { entered.await() }
        assertTrue(manager.keepAliveActive)
        assertEquals(1, starts)

        manager.cancelAndJoin()

        assertEquals(1, starts)
        assertEquals(1, stops)
        assertFalse(manager.keepAliveActive)
        assertFalse(manager.isDownloadActive)
        assertTrue(
            transferFinallyAt > 0L && keepAliveStopAt >= transferFinallyAt,
            "FGS/keep-alive must stop only after the download job finally unwinds",
        )
        scope.cancel()
    }

    @Test
    fun deniedBluetooth_surfacesErrorWithoutStartingTransfer() = runBlocking {
        var transferRuns = 0
        var stops = 0
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val manager = SessionDownloadManager(
            scope = scope,
            localeTag = { "en" },
            transfer = SessionDownloadTransfer { _, _ ->
                transferRuns++
                error("should not run")
            },
            keepAlive = SessionDownloadKeepAliveGate(
                startPlatform = { SessionDownloadKeepAliveStart.DeniedBluetooth },
                stopPlatform = { stops++ },
            ),
            installPromoteFailureHandler = {},
        )

        manager.start(request())
        manager.join()

        assertEquals(0, transferRuns)
        assertEquals(0, stops)
        assertFalse(manager.keepAliveActive)
        assertNull(manager.state.value.downloadingSessionId)
        assertTrue(
            manager.state.value.statusMessage?.contains("Bluetooth", ignoreCase = true) == true,
        )
        scope.cancel()
    }

    @Test
    fun promoteFailureHandler_surfacesErrorAndCancels() = runBlocking {
        var handler: ((String) -> Unit)? = null
        val entered = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val manager = SessionDownloadManager(
            scope = scope,
            localeTag = { "en" },
            transfer = SessionDownloadTransfer { _, _ ->
                entered.complete(Unit)
                awaitCancellation()
            },
            keepAlive = startedGate(),
            installPromoteFailureHandler = { handler = it },
        )

        manager.start(request())
        withTimeout(2_000) { entered.await() }
        assertTrue(manager.isDownloadActive)

        handler!!.invoke("Could not keep the download alive in the background")
        manager.join()

        assertFalse(manager.isDownloadActive)
        assertFalse(manager.keepAliveActive)
        assertEquals(
            "Could not keep the download alive in the background",
            manager.state.value.statusMessage,
        )
        scope.cancel()
    }

    @Test
    fun downloadSurvivesViewModelScopeCancellation() = runBlocking {
        var starts = 0
        var stops = 0
        val entered = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<String>()
        val appJob = SupervisorJob()
        val appScope = CoroutineScope(appJob + Dispatchers.Unconfined)
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
            keepAlive = startedGate(onStart = { starts++ }, onStop = { stops++ }),
            installPromoteFailureHandler = {},
        )

        viewModelScope.coroutineContext[Job]
        manager.start(request())
        withTimeout(2_000) { entered.await() }

        viewModelJob.cancel()
        assertTrue(viewModelJob.isCancelled)

        assertTrue(manager.isDownloadActive)
        assertTrue(manager.keepAliveActive)
        assertEquals("survived", withTimeout(2_000) { finished.await() })
        manager.join()
        assertEquals(1, starts)
        assertEquals(1, stops)
        assertEquals("survived", manager.state.value.lastDownloadedRecordingId)
        appJob.cancel()
    }

    @Test
    fun cancelFromNotificationHandler_usesCancelAndJoin() = runBlocking {
        var cancelInvocations = 0
        val entered = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        lateinit var manager: SessionDownloadManager
        manager = SessionDownloadManager(
            scope = scope,
            localeTag = { "en" },
            transfer = SessionDownloadTransfer { _, _ ->
                entered.complete(Unit)
                awaitCancellation()
            },
            keepAlive = startedGate(),
            installPromoteFailureHandler = {},
        )

        manager.start(request())
        withTimeout(2_000) { entered.await() }
        cancelInvocations++
        manager.cancelAndJoin()

        assertEquals(1, cancelInvocations)
        assertFalse(manager.isDownloadActive)
        assertFalse(manager.keepAliveActive)
        scope.cancel()
    }
}
