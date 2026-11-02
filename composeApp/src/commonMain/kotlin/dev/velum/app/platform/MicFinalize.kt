package dev.velum.app.platform

import dev.velum.ai.pipeline.TranscriptFormatter
import dev.velum.app.di.AppContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect

/** Stop/finalize the owned capture and queue only real audio; failures never become successful recovery. */
suspend fun finalizeMicRecording(
    container: AppContainer,
    recordingId: String,
    wavPath: String = "${recordingsDirectoryPath()}/$recordingId.wav",
    expectedSession: MicSessionIdentity? = container.micRecordings.owner.value?.takeIf { it.recordingId == recordingId }?.session,
    transcript: String? = null,
    markers: List<MicMarker> = container.micCapture.markers,
): MicStopResult {
    if (expectedSession == null) return MicStopResult.NotCapturing
    return container.micRecordings.withOwner(recordingId, wavPath) { owned ->
        if (owned.session != expectedSession || !container.micRecordings.isCurrent(owned)) return@withOwner MicStopResult.NotCapturing
        val stopped = try {
            container.micCapture.stop(owned.session)
        } catch (cancelled: CancellationException) {
            throw cancelled // Terminal state is retained by the platform for a later retry.
        } catch (_: Exception) { MicStopResult.Failed("") }
        if (owned.session != expectedSession || !container.micRecordings.isCurrent(owned)) return@withOwner MicStopResult.NotCapturing
        val result = when (stopped) {
            is MicStopResult.Stopped -> {
                if (stopped.session != owned.session || stopped.absolutePath != wavPath) return@withOwner MicStopResult.NotCapturing
                if (stopped.sizeBytes > 44) stopped else MicStopResult.Failed("")
            }
            else -> stopped
        }
        when (result) {
            is MicStopResult.Stopped -> {
                saveLiveText(container, recordingId, transcript, markers)
                container.repository.updateLocalPath(recordingId, result.absolutePath, result.durationSeconds, result.sizeBytes)
                container.repository.updateStatus(recordingId, "queued")
                container.enqueueProcessing(recordingId, templateId = "velum.meeting", retranscribe = true)
                container.micRecordings.release(owned)
            }
            is MicStopResult.Failed -> {
                saveLiveText(container, recordingId, transcript, markers)
                container.repository.updateStatus(recordingId, "failed")
                container.micRecordings.release(owned)
            }
            else -> Unit
        }
        result
    }
}

/** Markers have no other copy, so they are saved even when nothing was transcribed. */
private suspend fun saveLiveText(container: AppContainer, recordingId: String, transcript: String?, markers: List<MicMarker>) {
    if (markers.isEmpty()) {
        transcript?.let { container.repository.updateTranscript(recordingId, it) }
        return
    }
    val text = transcript ?: container.repository.getRecording(recordingId)?.transcript
    val section = buildString {
        appendLine(TranscriptFormatter.MARKERS_HEADING)
        markers.forEach { marker ->
            val seconds = marker.offsetMs / 1000
            append("- **").append(seconds / 60).append(':').append((seconds % 60).toString().padStart(2, '0')).append("**")
            if (marker.label.isNotBlank()) append(' ').append(marker.label)
            appendLine()
        }
    }
    container.repository.updateTranscript(recordingId, if (text.isNullOrBlank()) section else "${text.trimEnd()}\n\n$section")
}

/** Owned by the UI effect: no independent scope, and late start-result delivery is also observed. */
suspend fun observeMicFailures(container: AppContainer, onFailure: suspend (String) -> Unit) {
    combine(container.micRecordings.owner, container.micCapture.sessions) { owner, state -> owner to state }
        .collect { (owned, state) ->
            val failure = state as? MicSessionState.Failed ?: return@collect
            if (owned != null && failOwnedMicRecording(container, owned, failure)) onFailure(failure.message)
        }
}

internal suspend fun failOwnedMicRecording(container: AppContainer, owned: OwnedMicRecording, failure: MicSessionState.Failed): Boolean {
    if (failure.session != owned.session) return false
    var handled = false
    container.micRecordings.withOwner(owned.recordingId, owned.session.absolutePath) { current ->
        if (current == owned && container.micRecordings.isCurrent(owned) && container.micCapture.sessions.value == failure) {
            saveLiveText(container, owned.recordingId, null, container.micCapture.markers)
            container.repository.updateStatus(owned.recordingId, "failed")
            container.micRecordings.release(owned)
            handled = true
        }
        MicStopResult.NotCapturing
    }
    return handled
}
