package dev.velum.app.ui

/**
 * Resolved primary/secondary actions for one device-session row (#113).
 * Keeps the Device screen free of dead buttons: Open / Re-download / Download / Cancel.
 */
sealed class DeviceSessionRowAction {
    data class Open(val recordingId: String) : DeviceSessionRowAction()
    data class Redownload(val sessionId: Long) : DeviceSessionRowAction()
    data class Download(val sessionId: Long) : DeviceSessionRowAction()
    data object CancelDownload : DeviceSessionRowAction()
}

fun resolveDeviceSessionRowAction(
    sessionId: Long,
    libraryRecordingBySessionId: Map<Long, String>,
    downloadingSessionId: Long?,
): DeviceSessionRowAction {
    if (downloadingSessionId == sessionId) return DeviceSessionRowAction.CancelDownload
    val libraryId = libraryRecordingBySessionId[sessionId]
    return if (libraryId != null) {
        // Caller shows both Open (primary) and Re-download (secondary).
        DeviceSessionRowAction.Open(libraryId)
    } else {
        DeviceSessionRowAction.Download(sessionId)
    }
}

/** Secondary action when the session is already in the library. */
fun resolveDeviceSessionRedownloadAction(
    sessionId: Long,
    libraryRecordingBySessionId: Map<Long, String>,
): DeviceSessionRowAction.Redownload? {
    if (!libraryRecordingBySessionId.containsKey(sessionId)) return null
    return DeviceSessionRowAction.Redownload(sessionId)
}
