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
import java.nio.file.Path
import java.nio.file.AccessDeniedException
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class MicDraftRecoveryTest {
    @Test fun missing_draft_becomes_failed_once_without_deleting_a_row_or_queuing() = fixture {
        insert(); val first = reconcile()
        assertEquals(listOf(id), first.failedIds); assertEquals("failed", row()!!.status)
        assertTrue(container.repository.getAllRecordings().any { it.id == id })
        assertTrue(reconcile().failedIds.isEmpty()); assertEquals(0, enqueued); assertEquals(0, mic.starts)
    }
    @Test fun imports_advanced_jobs_and_drafts_with_progress_are_never_marked_failed() = fixture {
        for (source in listOf("import", "device")) {
            val other = "rec-$source-${UUID.randomUUID()}"; container.createLocalRecording(source = source, status = "recording", id = other)
        }
        for (status in listOf("queued", "generating", "ready", "failed")) container.createLocalRecording(status = status)
        insert(); container.repository.updateTranscript(id, "Synthetic progress")
        val before = container.repository.getAllRecordings(); assertTrue(reconcile().failedIds.isEmpty())
        assertEquals(before, container.repository.getAllRecordings()); assertEquals(0, enqueued)
    }
    // #205: markers saved while recording are not progress; the interrupted draft must still be failed.
    @Test fun a_draft_with_only_saved_markers_is_still_failed_when_the_audio_is_missing() = fixture {
        insert(); container.repository.updateTranscript(id, "## Markers\n- **0:30** synthetic note")
        assertEquals(listOf(id), reconcile().failedIds); assertEquals("failed", row()!!.status)
        assertTrue(row()!!.transcript!!.contains("## Markers")) // the markers are kept
    }
    @Test fun cancellation_before_insert_leaves_no_row_or_audio() = fixture {
        coroutineScope {
            val entered = CompletableDeferred<Unit>(); val gate = CompletableDeferred<Unit>()
            val job = async { startPhoneMicDraft(container, root.path, id, insertDraft = { entered.complete(Unit); gate.await() }) }
            entered.await(); job.cancelAndJoin()
            assertTrue(job.isCancelled); assertEquals(null, row()); assertFalse(audio.exists()); assertEquals(0, mic.starts)
        }
    }
    @Test fun committed_insert_is_protected_while_pending_and_cancelled_draft_is_reconciled_afterward() = fixture {
        coroutineScope {
            val entered = CompletableDeferred<Unit>(); val gate = CompletableDeferred<Unit>()
            val job = async { startPhoneMicDraft(container, root.path, id, insertDraft = { insert(); entered.complete(Unit); gate.await() }) }
            entered.await(); assertEquals(listOf(id), reconcile().protectedIds); assertEquals("recording", row()!!.status)
            job.cancelAndJoin(); assertTrue(job.isCancelled); assertEquals("failed", row()!!.status)
            assertFalse(audio.exists()); assertEquals(0, mic.starts); assertEquals(0, enqueued)
        }
    }
    @Test fun cancellation_waiting_for_service_acknowledgement_preserves_empty_header_and_marks_unstarted_draft_failed() = fixture {
        coroutineScope {
            val before = emptyWave().readBytes(); mic.entered = CompletableDeferred(); mic.gate = CompletableDeferred()
            val job = async { startPhoneMicDraft(container, root.path, id) }
            mic.entered!!.await(); job.cancelAndJoin()
            assertTrue(job.isCancelled); assertEquals("failed", row()!!.status)
            assertContentEquals(before, audio.readBytes()); assertEquals(0, enqueued)
        }
    }
    @Test fun unacknowledged_start_cancellation_is_deferred_instead_of_misclassifying_an_active_service() = fixture {
        coroutineScope {
            mic.entered = CompletableDeferred(); mic.gate = CompletableDeferred(); mic.ackCancellation = false
            val job = async { startPhoneMicDraft(container, root.path, id) }
            mic.entered!!.await(); job.cancelAndJoin()
            assertEquals("recording", row()!!.status); assertEquals(listOf(id), reconcile().protectedIds)
            assertEquals(0, enqueued)
        }
    }
    @Test fun cancellation_after_owner_assignment_cannot_fail_the_active_recording() = fixture {
        coroutineScope {
            val entered = CompletableDeferred<Unit>(); val gate = CompletableDeferred<Unit>()
            val job = async { startPhoneMicDraft(container, root.path, id, persistStarted = { _, _ -> entered.complete(Unit); gate.await() }) }
            entered.await(); job.cancelAndJoin()
            assertEquals(id, container.activeMicRecordingId); assertEquals("recording", row()!!.status)
            assertEquals(listOf(id), reconcile().protectedIds); assertEquals(0, enqueued)
        }
    }
    @Test fun owner_and_starting_recording_or_paused_service_evidence_protect_missing_audio() = fixture {
        insert(); container.micRecordings.startOrAttach(id, audio.path)
        assertEquals(listOf(id), reconcile().protectedIds)
        container.micRecordings.release(container.micRecordings.owner.value!!)
        val session = MicSessionIdentity(50, File(root, "$id.WaV.PaRtIaL").path)
        for (state in listOf(MicSessionState.Starting(session), MicSessionState.Recording(session.absolutePath, 0, 50), MicSessionState.Paused(session.absolutePath, 0, 50))) {
            mic.sessions.value = state; assertEquals(listOf(id), reconcile().protectedIds)
        }
        assertEquals("recording", row()!!.status); assertEquals(0, enqueued)
    }
    @Test fun valid_final_partial_and_nonempty_invalid_files_are_preserved_and_not_auto_enqueued() = fixture {
        insert()
        for (file in listOf(audio, File(root, "$id.WaV.PaRtIaL"))) {
            WavWriter(file.path).apply { writePcm(ByteArray(32) { 7 }) }.finish()
            val before = file.readBytes(); assertEquals(listOf(id), reconcile().deferredIds)
            assertEquals("recording", row()!!.status); assertContentEquals(before, file.readBytes()); file.delete()
        }
        audio.writeBytes(byteArrayOf(1, 2, 3)); val before = audio.readBytes()
        assertEquals(listOf(id), reconcile().deferredIds); assertContentEquals(before, audio.readBytes()); assertEquals(0, enqueued)
    }
    @Test fun zero_byte_and_valid_empty_header_files_are_kept_when_draft_is_marked_failed() = fixture {
        for (header in listOf(false, true)) {
            if (row() == null) insert() else container.repository.updateStatus(id, "recording")
            if (header) emptyWave() else audio.writeBytes(byteArrayOf())
            val before = audio.readBytes(); assertEquals(listOf(id), reconcile().failedIds)
            assertTrue(audio.exists()); assertContentEquals(before, audio.readBytes())
        }
    }
    @Test fun inaccessible_paths_and_foreign_metadata_paths_are_deferred_without_any_row_change() = fixture {
        insert(); val before = row()
        assertEquals(listOf(id), reconcileInterruptedMicDrafts(container, root.path, inspectAudio = { MicDraftAudioEvidence.Unavailable }).deferredIds)
        assertEquals(before, row())
        container.repository.updateLocalPath(id, "/synthetic-unrelated/not-accessed.wav", 0, 0)
        val foreign = row(); var calls = 0
        assertEquals(listOf(id), reconcileInterruptedMicDrafts(container, root.path, inspectAudio = { calls++; error("Must not inspect foreign path") }).deferredIds)
        assertEquals(0, calls); assertEquals(foreign, row())
    }
    @Test fun changed_database_snapshot_prevents_overwriting_an_in_progress_import_or_job() = fixture {
        insert()
        val report = reconcileInterruptedMicDrafts(container, root.path, inspectAudio = {
            runBlocking { container.repository.updateStatus(id, "queued") }
            MicDraftAudioEvidence.MissingOrEmpty
        })
        assertEquals(listOf(id), report.deferredIds); assertEquals("queued", row()!!.status); assertEquals(0, enqueued)
    }
    @Test fun cancellation_during_file_inspection_propagates_without_marking_the_draft_failed() = fixture {
        insert(); val before = row()
        assertFailsWith<CancellationException> { reconcileInterruptedMicDrafts(container, root.path, inspectAudio = { throw CancellationException("Synthetic cancellation") }) }
        assertEquals(before, row()); assertEquals(0, enqueued)
    }
    @Test fun denied_start_keeps_a_visible_failed_row_and_failed_draft_cannot_start_a_new_capture() = fixture {
        mic.denied = true
        assertEquals(MicStartResult.PermissionRequired, startPhoneMicDraft(container, root.path, id).result)
        assertEquals("failed", row()!!.status); assertTrue(container.repository.getAllRecordings().any { it.id == id })
        mic.denied = false; val count = mic.starts
        assertIs<MicStartResult.Failed>(container.micRecordings.startOrAttach(id, audio.path)); assertEquals(count, mic.starts)
    }
    @Test fun pending_draft_with_valid_partial_cannot_be_queued_by_startup_file_recovery() = fixture {
        coroutineScope {
            val entered = CompletableDeferred<Unit>(); val gate = CompletableDeferred<Unit>()
            val partial = File(root, "$id.wav.partial")
            val job = async { startPhoneMicDraft(container, root.path, id, insertDraft = {
                insert(); WavWriter(partial.path).apply { writePcm(ByteArray(32)) }.finish(); entered.complete(Unit); gate.await()
            }) }
            entered.await(); val before = partial.readBytes()
            val report = recoverPartialMicRecordings(root.path, container.repository, { enqueued++ }, protectedRecording = container.micRecordings::protects)
            assertEquals(1, report.skippedActive); assertEquals(0, enqueued); assertEquals("recording", row()!!.status)
            job.cancelAndJoin(); assertEquals("recording", row()!!.status); assertContentEquals(before, partial.readBytes())
        }
    }
    @Test fun readonly_inspector_defers_synthetic_io_errors_and_never_follows_links_or_directories() = fixture {
        val io = object : MicDraftFileIo() { override fun attributes(path: Path): java.nio.file.attribute.BasicFileAttributes = throw AccessDeniedException("synthetic") }
        assertEquals(MicDraftAudioEvidence.Unavailable, inspectJvmMicDraftAudio(audio.path, io))
        audio.mkdir(); assertEquals(MicDraftAudioEvidence.Unavailable, inspectJvmMicDraftAudio(audio.path)); audio.delete()
        val target = File(root, "synthetic-target").also { it.writeBytes(byteArrayOf(1, 2)) }
        Files.createSymbolicLink(audio.toPath(), target.toPath())
        assertEquals(MicDraftAudioEvidence.Unavailable, inspectJvmMicDraftAudio(audio.path)); assertContentEquals(byteArrayOf(1, 2), target.readBytes())
    }

    @Test fun cancellation_after_insert_before_service_delivery_keeps_the_row_and_never_launches_capture() = fixture {
        coroutineScope {
            val job = async { startPhoneMicDraft(container, root.path, id, insertDraft = {
                insert(); currentCoroutineContext().cancel()
            }) }
            job.join(); assertTrue(job.isCancelled); assertEquals("failed", row()!!.status)
            assertEquals(0, mic.starts); assertFalse(audio.exists()); assertEquals(0, enqueued)
        }
    }
    @Test fun cancellation_after_start_metadata_commit_preserves_the_active_owner_and_path() = fixture {
        coroutineScope {
            val entered = CompletableDeferred<Unit>(); val gate = CompletableDeferred<Unit>()
            val job = async { startPhoneMicDraft(container, root.path, id, persistStarted = { recordingId, path ->
                container.repository.updateLocalPath(recordingId, path, 0, 0); entered.complete(Unit); gate.await()
            }) }
            entered.await(); job.cancelAndJoin()
            assertEquals(id, container.activeMicRecordingId); assertEquals(audio.path, row()!!.local_path)
            assertEquals("recording", row()!!.status); assertEquals(listOf(id), reconcile().protectedIds)
        }
    }
    @Test fun a_file_that_grows_during_readonly_empty_inspection_is_deferred_without_writing_audio() = fixture {
        audio.writeBytes(byteArrayOf()); var changed = false
        val io = object : MicDraftFileIo() {
            override fun attributes(path: Path): java.nio.file.attribute.BasicFileAttributes {
                val before = super.attributes(path)
                if (path == audio.toPath() && !changed) { changed = true; audio.appendBytes(byteArrayOf(3, 4)) }
                return before
            }
        }
        assertEquals(MicDraftAudioEvidence.Unavailable, inspectJvmMicDraftAudio(audio.path, io))
        assertContentEquals(byteArrayOf(3, 4), audio.readBytes())
    }

    @Test fun unsupported_capture_never_creates_an_unstartable_recording_draft() = fixture {
        mic.isSupported = false
        assertEquals(MicStartResult.Unsupported, startPhoneMicDraft(container, root.path, id).result)
        assertEquals(null, row()); assertEquals(0, mic.starts); assertEquals(0, enqueued); assertFalse(audio.exists())
    }

    private fun fixture(block: suspend Fixture.() -> Unit) = runBlocking {
        withTimeout(20_000) {
            val context = ApplicationProvider.getApplicationContext<Application>(); context.deleteDatabase("velum.db")
            val f = Fixture(context)
            try { f.block() } finally { f.container.databaseHandle.driver.close(); f.root.deleteRecursively() }
        }
    }
    private class Fixture(context: Application) {
        val root = Files.createTempDirectory("velum-draft-synthetic").toFile()
        val id = "rec-draft-${UUID.randomUUID()}"; val audio = File(root, "$id.wav")
        val mic = SyntheticCapture(); var enqueued = 0
        val container = AppContainer(InMemorySecureStore(), DriverFactory(context), modelStoreRoot = ModelStoreDir(File(root, "models").path), micCapture = mic,
            recordingJobSchedulerFactory = { _, _ -> RecordingJobScheduler { _, _, _, _ -> enqueued++; dev.velum.ai.pipeline.EnqueueResult.STARTED_OR_REPLACED } })
        suspend fun insert() { container.createLocalRecording(status = "recording", id = id) }
        suspend fun row() = container.repository.getRecording(id)
        suspend fun reconcile() = reconcileInterruptedMicDrafts(container, root.path)
        fun emptyWave() = audio.also { WavWriter(it.path).finish() }
    }
    private class SyntheticCapture : MicCapture {
        override val sessions = MutableStateFlow<MicSessionState>(MicSessionState.Idle)
        override var isSupported = true
        override val isCapturing get() = sessions.value is MicSessionState.Starting || sessions.value is MicSessionState.Recording || sessions.value is MicSessionState.Paused
        override val isPaused get() = sessions.value is MicSessionState.Paused
        override val markers = emptyList<MicMarker>(); override var onPcmChunk: ((ByteArray) -> Unit)? = null
        var starts = 0; var denied = false; var ackCancellation = true
        var entered: CompletableDeferred<Unit>? = null; var gate: CompletableDeferred<Unit>? = null
        override suspend fun start(absolutePath: String, inputSource: MicInputSource): MicStartResult {
            starts++; if (denied) return MicStartResult.PermissionRequired
            val session = MicSessionIdentity(starts.toLong(), absolutePath); sessions.value = MicSessionState.Starting(session)
            entered?.complete(Unit)
            try { gate?.await() } catch (cancelled: CancellationException) {
                if (ackCancellation) sessions.value = MicSessionState.Failed("Synthetic cancelled", session = session)
                throw cancelled
            }
            sessions.value = MicSessionState.Recording(absolutePath, 0, session.token); return MicStartResult.Started(session)
        }
        override suspend fun stop() = error("Draft reconciliation must not stop capture")
        override suspend fun pause() = MicPauseResult.Unsupported
        override suspend fun resume() = MicPauseResult.Unsupported
        override suspend fun addMarker(label: String) = MicMarkerResult.Unsupported
    }
}
