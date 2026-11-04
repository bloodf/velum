package dev.velum.app.platform

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import dev.velum.ai.local.ModelStoreDir
import dev.velum.ai.pipeline.RecordingJobScheduler
import dev.velum.app.di.AppContainer
import dev.velum.core.data.DriverFactory
import dev.velum.core.security.InMemorySecureStore
import java.io.File
import java.nio.file.Files
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class MicFinalizeTest {
    @Test fun a_non_owner_cannot_stop_or_consume_another_recordings_audio() = fixture {
        val other = container.createLocalRecording(status = "recording")
        val bytes = wav.readBytes(); mic.result = MicStopResult.Stopped(wav.path, 0, wav.length())
        assertEquals(MicStopResult.NotCapturing, finalizeMicRecording(container, other, File(root, "$other.wav").path))
        assertEquals(0, mic.stops); assertEquals(0, queued); assertEquals(id, container.activeMicRecordingId)
        assertEquals(null, container.repository.observeRecording(other).first()!!.local_path)
        assertEquals("recording", recording().status); assertContentEquals(bytes, wav.readBytes())
    }
    @Test fun a_matching_owner_cannot_attach_a_different_output_path() = fixture {
        val foreign = File(root, "foreign.wav").also { WavWriter(it.path).apply { writePcm(ByteArray(32) { 8 }) }.finish() }
        val bytes = wav.readBytes(); val foreignBytes = foreign.readBytes()
        mic.result = MicStopResult.Stopped(foreign.path, 0, foreign.length())
        assertEquals(MicStopResult.NotCapturing, finalizeMicRecording(container, id, wav.path))
        assertEquals(0, queued); assertEquals(null, recording().local_path)
        assertContentEquals(bytes, wav.readBytes()); assertContentEquals(foreignBytes, foreign.readBytes())
    }
    @Test fun failed_stop_preserves_existing_pcm_and_never_recovers_or_queues_it_as_success() = fixture {
        val bytes = wav.readBytes(); mic.result = MicStopResult.Failed("Synthetic capture failed")
        assertEquals(mic.stoppedResult, finalizeMicRecording(container, id, wav.path))
        assertEquals("failed", recording().status); assertEquals(0, queued)
        assertEquals(null, container.activeMicRecordingId); assertContentEquals(bytes, wav.readBytes())
    }
    @Test fun normal_stop_updates_metadata_and_queues_exactly_once() = fixture {
        mic.result = MicStopResult.Stopped(wav.path, 0, wav.length())
        assertEquals(mic.stoppedResult, finalizeMicRecording(container, id, wav.path))
        assertEquals("queued", recording().status); assertEquals(wav.path, recording().local_path)
        assertEquals(wav.length(), recording().size_bytes); assertEquals(1, queued)
        assertEquals(null, container.activeMicRecordingId)
    }
    @Test fun notification_stop_can_recover_real_existing_audio_and_queue_it() = fixture {
        mic.result = MicStopResult.Stopped(wav.path, 0, wav.length())
        mic.finishFromNotification()
        assertIs<MicStopResult.Stopped>(finalizeMicRecording(container, id, wav.path))
        assertEquals("queued", recording().status); assertEquals(1, queued)
    }
    @Test fun an_unidentified_terminal_result_never_falls_back_to_missing_or_unrelated_audio() = fixture {
        wav.delete(); mic.result = MicStopResult.NotCapturing
        assertEquals(MicStopResult.NotCapturing, finalizeMicRecording(container, id, wav.path))
        assertFalse(wav.exists()); assertEquals("recording", recording().status); assertEquals(0, queued)
        assertEquals(id, container.activeMicRecordingId)
    }
    @Test fun a_wrong_requested_path_is_rejected_before_stopping_the_owned_capture() = fixture {
        val before = wav.readBytes()
        assertEquals(MicStopResult.NotCapturing, finalizeMicRecording(container, id, File(root, "other.wav").path))
        assertEquals(0, mic.stops); assertEquals(0, queued); assertEquals(id, container.activeMicRecordingId)
        assertContentEquals(before, wav.readBytes())
    }
    @Test fun an_empty_wav_is_not_reported_as_a_successful_capture() = fixture {
        WavWriter(wav.path, 16_000).finish()
        mic.result = MicStopResult.Stopped(wav.path, 0, 44)
        assertIs<MicStopResult.Failed>(finalizeMicRecording(container, id, wav.path))
        assertEquals("failed", recording().status); assertEquals(0, queued); assertEquals(44L, wav.length())
    }
    @Test fun cancellation_keeps_ownership_for_later_terminal_result_and_never_queues() = fixture {
        val bytes = wav.readBytes(); mic.failure = CancellationException("Synthetic cancellation")
        try { finalizeMicRecording(container, id, wav.path); error("Expected cancellation") }
        catch (_: CancellationException) { }
        assertEquals(id, container.activeMicRecordingId); assertEquals("recording", recording().status)
        assertEquals(0, queued); assertContentEquals(bytes, wav.readBytes())
    }
    @Test fun a_throwing_stop_is_reported_as_failure_and_never_queues_or_changes_audio_bytes() = fixture {
        val bytes = wav.readBytes(); mic.failure = IllegalStateException("Synthetic private detail")
        val result = assertIs<MicStopResult.Failed>(finalizeMicRecording(container, id, wav.path))
        assertEquals("", result.message); assertEquals("failed", recording().status); assertEquals(0, queued)
        assertContentEquals(bytes, wav.readBytes())
    }
    @Test fun repeated_finalize_is_idempotent_and_does_not_schedule_twice() = fixture {
        mic.result = MicStopResult.Stopped(wav.path, 0, wav.length())
        finalizeMicRecording(container, id, wav.path)
        assertEquals(MicStopResult.NotCapturing, finalizeMicRecording(container, id, wav.path))
        assertEquals(1, queued); assertEquals(1, mic.stops); assertEquals("queued", recording().status)
    }

    @Test fun post_start_failure_is_reported_and_marks_the_owned_recording_failed_without_queueing() = fixture {
        kotlinx.coroutines.coroutineScope {
            val shown = CompletableDeferred<String>()
            val observer = launch(start = CoroutineStart.UNDISPATCHED) { observeMicFailures(container) { shown.complete(it) } }
            try {
                mic.fail("Synthetic permission revoked")
                assertEquals("Synthetic permission revoked", withTimeout(10_000) { shown.await() })
                assertEquals("failed", recording().status); assertEquals(null, container.activeMicRecordingId); assertEquals(0, queued)
            } finally { observer.cancelAndJoin() }
        }
    }
    @Test fun a_failure_that_arrives_before_the_start_result_still_fails_the_later_owned_recording() = fixture {
        kotlinx.coroutines.coroutineScope {
            container.micRecordings.release(container.micRecordings.owner.value!!)
            mic.failAfterStart = "Synthetic early failure"
            val shown = CompletableDeferred<String>()
            val observer = launch(start = CoroutineStart.UNDISPATCHED) { observeMicFailures(container) { shown.complete(it) } }
            try {
                container.micRecordings.startOrAttach(id, wav.path)
                assertEquals("Synthetic early failure", withTimeout(10_000) { shown.await() })
                assertEquals("failed", recording().status); assertEquals(0, queued)
            } finally { observer.cancelAndJoin() }
        }
    }
    @Test fun concurrent_finalizers_stop_and_queue_the_owned_session_once() = fixture {
        kotlinx.coroutines.coroutineScope {
            mic.result = MicStopResult.Stopped(wav.path, 0, wav.length())
            mic.entered = CompletableDeferred(); mic.gate = CompletableDeferred()
            val first = async(start = CoroutineStart.UNDISPATCHED) { finalizeMicRecording(container, id, wav.path) }
            mic.entered!!.await()
            val second = async(start = CoroutineStart.UNDISPATCHED) { finalizeMicRecording(container, id, wav.path) }
            assertFalse(second.isCompleted); assertEquals(0, queued)
            mic.gate!!.complete(Unit)
            assertIs<MicStopResult.Stopped>(first.await()); assertEquals(MicStopResult.NotCapturing, second.await())
            assertEquals(1, queued); assertEquals(1, mic.stops)
        }
    }
    @Test fun cancellation_after_terminal_delivery_keeps_the_result_for_an_idempotent_retry() = fixture {
        kotlinx.coroutines.coroutineScope {
            val original = wav.readBytes(); mic.result = MicStopResult.Stopped(wav.path, 0, wav.length())
            mic.entered = CompletableDeferred(); mic.gate = CompletableDeferred()
            val first = async(start = CoroutineStart.UNDISPATCHED) { finalizeMicRecording(container, id, wav.path) }
            mic.entered!!.await(); first.cancelAndJoin()
            assertEquals(id, container.activeMicRecordingId); assertEquals(0, queued)
            assertIs<MicStopResult.Stopped>(finalizeMicRecording(container, id, wav.path))
            assertEquals(1, queued); assertEquals(1, mic.stops); assertContentEquals(original, wav.readBytes())
        }
    }
    @Test fun cancellation_after_enqueue_submission_releases_mic_after_acceptance() = fixture {
        kotlinx.coroutines.coroutineScope {
            mic.result = MicStopResult.Stopped(wav.path, 0, wav.length())
            enqueueEntered = CompletableDeferred()
            enqueueGate = CompletableDeferred()
            val finishing = launch { finalizeMicRecording(container, id, wav.path) }
            withTimeout(5_000) { enqueueEntered!!.await() }
            finishing.cancel()
            enqueueGate!!.complete(Unit)
            finishing.join()
            assertEquals(true, finishing.isCancelled)
            assertEquals(1, queued)
            assertEquals("queued", recording().status)
            assertEquals(null, container.activeMicRecordingId)
        }
    }
    @Test fun a_new_recording_cannot_rebind_ownership_during_an_in_flight_finalization() = fixture {
        kotlinx.coroutines.coroutineScope {
            val other = container.createLocalRecording(status = "recording")
            mic.result = MicStopResult.Stopped(wav.path, 0, wav.length())
            mic.entered = CompletableDeferred(); mic.gate = CompletableDeferred()
            val finishing = async(start = CoroutineStart.UNDISPATCHED) { finalizeMicRecording(container, id, wav.path) }
            mic.entered!!.await()
            val next = async(start = CoroutineStart.UNDISPATCHED) { container.micRecordings.startOrAttach(other, File(root, "$other.wav").path) }
            assertFalse(next.isCompleted); assertEquals(id, container.activeMicRecordingId)
            mic.gate!!.complete(Unit); finishing.await()
            assertIs<MicStartResult.Started>(next.await()); assertEquals(other, container.activeMicRecordingId)
            assertEquals("recording", container.repository.observeRecording(other).first()!!.status)
            assertEquals("queued", recording().status); assertEquals(1, queued)
        }
    }
    @Test fun an_old_finalization_and_failure_cannot_mutate_a_new_generation_of_the_same_recording() = fixture {
        val old = container.micRecordings.owner.value!!
        mic.result = MicStopResult.Stopped(wav.path, 0, wav.length())
        finalizeMicRecording(container, id, wav.path)
        container.repository.updateStatus(id, "recording")
        val next = assertIs<MicStartResult.Started>(container.micRecordings.startOrAttach(id, wav.path))
        assertFalse(old.session == next.session)
        val newerOwner = container.micRecordings.owner.value!!; val before = wav.readBytes()
        assertEquals(MicStopResult.NotCapturing, finalizeMicRecording(container, id, wav.path, old.session, transcript = "Synthetic stale overwrite"))
        val delayed = MicSessionState.Failed("Synthetic stale failure", session = old.session)
        assertFalse(failOwnedMicRecording(container, old, delayed))
        assertFalse(failOwnedMicRecording(container, newerOwner, delayed))
        assertFalse(container.micRecordings.withCurrentOwner(id, old.session) { error("Stale start metadata must not write") })
        assertEquals(null, recording().transcript)
        assertEquals(newerOwner, container.micRecordings.owner.value); assertEquals("recording", recording().status)
        assertEquals(1, queued); assertEquals(1, mic.stops); assertContentEquals(before, wav.readBytes())
    }

    @Test fun a_late_finish_after_B_starts_cannot_change_completed_A_or_active_B() = fixture {
        val old = container.micRecordings.owner.value!!
        mic.result = MicStopResult.Stopped(wav.path, 0, wav.length())
        finalizeMicRecording(container, id, wav.path, old.session, transcript = "Synthetic completed A")
        val other = container.createLocalRecording(status = "recording")
        val next = assertIs<MicStartResult.Started>(container.micRecordings.startOrAttach(other, File(root, "$other.wav").path))
        val beforeA = recording(); val beforeB = container.repository.observeRecording(other).first()
        assertEquals(MicStopResult.NotCapturing, finalizeMicRecording(container, id, wav.path, old.session, transcript = "Synthetic late overwrite"))
        assertEquals(beforeA, recording()); assertEquals(beforeB, container.repository.observeRecording(other).first())
        assertEquals(next.session, container.micRecordings.owner.value!!.session)
        assertEquals(other, container.activeMicRecordingId); assertEquals(1, mic.stops); assertEquals(1, queued)
    }

    private fun fixture(block: suspend Fixture.() -> Unit) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.deleteDatabase("velum.db")
        val fixture = Fixture(context)
        try { fixture.id = fixture.container.createLocalRecording(status = "recording"); fixture.container.micRecordings.startOrAttach(fixture.id, fixture.wav.path); fixture.block() }
        finally { fixture.container.databaseHandle.driver.close(); fixture.root.deleteRecursively() }
    }
    private class Fixture(context: Application) {
        val root = Files.createTempDirectory("velum-mic-finalize-test").toFile()
        val wav = File(root, "synthetic.wav").also { WavWriter(it.path, 16_000).apply { writePcm(ByteArray(32)) }.finish() }
        val mic = FakeCapture()
        var queued = 0
        var enqueueEntered: CompletableDeferred<Unit>? = null
        var enqueueGate: CompletableDeferred<Unit>? = null
        val container = AppContainer(InMemorySecureStore(), DriverFactory(context),
            modelStoreRoot = ModelStoreDir(File(root, "models").path), micCapture = mic,
            recordingJobSchedulerFactory = { _, _ -> object : RecordingJobScheduler {
                override fun enqueue(recordingId: String, templateId: String?, retranscribe: Boolean, apiModelOverride: dev.velum.core.settings.ApiModelOverride?) { queued++ }
                override suspend fun enqueueSaved(recordingId: String, templateId: String?, retranscribe: Boolean, apiModelOverride: dev.velum.core.settings.ApiModelOverride?, keepExisting: Boolean) {
                    enqueueEntered?.complete(Unit)
                    enqueueGate?.await()
                    enqueue(recordingId, templateId, retranscribe, apiModelOverride)
                }
            } })
        lateinit var id: String
        suspend fun recording() = container.repository.observeRecording(id).first()!!
    }
    private class FakeCapture : MicCapture {
        override val failures = MutableStateFlow<String?>(null)
        override val sessions = MutableStateFlow<MicSessionState>(MicSessionState.Idle)
        var identity = MicSessionIdentity(0, "")
        var failAfterStart: String? = null
        var gate: CompletableDeferred<Unit>? = null
        var entered: CompletableDeferred<Unit>? = null
        val stoppedResult: MicStopResult get() = (result as? MicStopResult.Stopped)?.copy(session = identity) ?: result
        fun fail(message: String) { isCapturing = false; sessions.value = MicSessionState.Failed(message, session = identity); failures.value = message }
        fun finishFromNotification() { isCapturing = false; val stopped = stoppedResult as MicStopResult.Stopped; sessions.value = MicSessionState.Stopped(stopped.absolutePath, stopped.durationSeconds, stopped.sizeBytes, identity.token) }
        override val isSupported = true
        override var isCapturing = true
        override val isPaused = false
        override val markers = emptyList<MicMarker>()
        override var onPcmChunk: ((ByteArray) -> Unit)? = null
        var result: MicStopResult = MicStopResult.NotCapturing
        var failure: Exception? = null
        var stops = 0
        override suspend fun start(absolutePath: String, inputSource: MicInputSource): MicStartResult {
            identity = MicSessionIdentity(identity.token + 1, absolutePath); isCapturing = true
            sessions.value = MicSessionState.Recording(absolutePath, 0, identity.token)
            failAfterStart?.let { fail(it) }
            return MicStartResult.Started(identity)
        }
        override suspend fun pause() = MicPauseResult.Unsupported
        override suspend fun resume() = MicPauseResult.Unsupported
        override suspend fun addMarker(label: String) = MicMarkerResult.Unsupported
        override suspend fun stop(): MicStopResult {
            if (sessions.value is MicSessionState.Stopped) return stoppedResult
            stops++; failure?.let { throw it }; isCapturing = false
            when (val stopped = stoppedResult) {
                is MicStopResult.Stopped -> sessions.value = MicSessionState.Stopped(stopped.absolutePath, stopped.durationSeconds, stopped.sizeBytes, identity.token)
                is MicStopResult.Failed -> fail(stopped.message)
                else -> Unit
            }
            entered?.complete(Unit); gate?.await()
            return stoppedResult
        }
    }
}
