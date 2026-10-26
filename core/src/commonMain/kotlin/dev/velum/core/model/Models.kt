package dev.velum.core.model

import kotlinx.serialization.Serializable

@Serializable
data class Recording(
    val id: String,
    val sessionId: Long,
    val deviceSerial: String?,
    val title: String?,
    val startedAtEpochSeconds: Long,
    val durationSeconds: Long?,
    val sizeBytes: Long?,
    val localPath: String?,
    val downloadState: DownloadState = DownloadState.NotDownloaded,
)

@Serializable
enum class DownloadState {
    NotDownloaded,
    Downloading,
    Downloaded,
    Failed,
}

@Serializable
data class Transcript(
    val recordingId: String,
    val text: String,
    val model: String,
    val createdAtEpochSeconds: Long,
)

@Serializable
data class Summary(
    val id: String,
    val recordingId: String,
    val templateId: String?,
    val markdown: String,
    val model: String,
    val createdAtEpochSeconds: Long,
)

@Serializable
data class Template(
    val id: String,
    val name: String,
    val category: String,
    val description: String,
    val systemPrompt: String,
)

@Serializable
data class ConnectedDevice(
    val id: String,
    val name: String?,
    val serial: String?,
    val modelCode: Int?,
    val protocolVersion: Int?,
    val rssi: Int?,
    val batteryPercent: Int? = null,
)

@Serializable
data class AiProviderSettings(
    val baseUrl: String = "https://api.openai.com/v1",
    /** Never log or commit; stored via [dev.velum.core.security.SecureStore]. */
    val hasApiKey: Boolean = false,
    val transcriptionModel: String? = null,
    val chatModel: String? = null,
    val cachedModels: List<AiModelInfo> = emptyList(),
)

@Serializable
data class AiModelInfo(
    val id: String,
    val ownedBy: String? = null,
)
