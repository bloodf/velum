package dev.velum.app.platform

import dev.velum.app.di.AppContainer
import dev.velum.core.data.RecordingIds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

enum class MicDraftAudioEvidence { MissingOrEmpty, Present, Unavailable }
internal expect fun inspectMicDraftAudio(absolutePath: String): MicDraftAudioEvidence

data class MicDraftReconciliationReport(
    val failedIds: List<String> = emptyList(),
    val deferredIds: List<String> = emptyList(),
    val protectedIds: List<String> = emptyList(),
)

/** Never deletes or queues anything. Only an unchanged, unstarted phone draft can become failed. */
suspend fun reconcileInterruptedMicDrafts(
    container: AppContainer,
    directory: String,
    ids: Set<String>? = null,
    inspectAudio: (String) -> MicDraftAudioEvidence = ::inspectMicDraftAudio,
): MicDraftReconciliationReport = withContext(Dispatchers.IO) {
    container.micRecordings.inspectInterruptedDrafts {
        val rows = if (ids == null) container.repository.getAllRecordings() else ids.mapNotNull { container.repository.getRecording(it) }
        val failed = mutableListOf<String>(); val deferred = mutableListOf<String>(); val protected = mutableListOf<String>()
        for (row in rows) {
            currentCoroutineContext().ensureActive()
            if (row.source != "phone" || row.status != "recording" || row.duration_seconds != 0L || row.size_bytes != 0L ||
                !row.transcript.isNullOrBlank() || !row.summary_markdown.isNullOrBlank()) continue
            fun active(): Boolean {
                val state = container.micCapture.sessions.value
                val activeSession = if (state is MicSessionState.Starting || state is MicSessionState.Recording || state is MicSessionState.Paused) state.session else null
                val name = activeSession?.absolutePath?.substringAfterLast('/')
                return container.micRecordings.protects(row.id) || name?.equals("${row.id}.wav", true) == true || name?.equals("${row.id}.wav.partial", true) == true
            }
            if (active()) { protected += row.id; continue }
            if (!row.id.startsWith("rec-") || '/' in row.id || '\\' in row.id) { deferred += row.id; continue }
            val path = "$directory/${row.id}.wav"
            if (row.local_path != null && row.local_path != path) { deferred += row.id; continue }
            val evidence = inspectAudio(path)
            if (active()) { protected += row.id; continue }
            if (evidence != MicDraftAudioEvidence.MissingOrEmpty) { deferred += row.id; continue }
            if (container.repository.failInterruptedPhoneDraft(row)) failed += row.id else deferred += row.id
        }
        MicDraftReconciliationReport(failed, deferred, protected)
    }
}

internal data class PhoneMicDraftStart(val recordingId: String, val result: MicStartResult)

/** Allocate identity before the first suspending insert so cancellation after commit is recoverable. */
internal suspend fun startPhoneMicDraft(
    container: AppContainer,
    directory: String = recordingsDirectoryPath(),
    id: String = RecordingIds.newId(),
    insertDraft: suspend (String) -> Unit = { container.createLocalRecording(source = "phone", status = "recording", id = it) },
    persistStarted: suspend (String, String) -> Unit = { recordingId, path -> container.repository.updateLocalPath(recordingId, path, 0, 0) },
): PhoneMicDraftStart {
    suspend fun reconcile() { reconcileInterruptedMicDrafts(container, directory, setOf(id)) }
    if (!container.micCapture.isSupported) return PhoneMicDraftStart(id, MicStartResult.Unsupported)
    try {
        val result = container.micRecordings.withPendingDraft(id) {
            insertDraft(id)
            val path = "$directory/$id.wav"
            val started = container.micRecordings.startOrAttach(id, path)
            if (started is MicStartResult.Started) container.micRecordings.withCurrentOwner(id, started.session) { persistStarted(id, path) }
            started
        }
        if (result !is MicStartResult.Started) reconcile()
        return PhoneMicDraftStart(id, result)
    } catch (cancelled: CancellationException) {
        withContext(NonCancellable) { try { reconcile() } catch (_: Exception) { /* Retry at startup; preserve the original cancellation. */ } }
        throw cancelled
    }
}
