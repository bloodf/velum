package dev.velum.core.autoflow

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Current bundled AutoFlow template schema version. */
const val AUTOFLOW_TEMPLATE_SCHEMA_VERSION: Int = 1

@Serializable
data class LocalizedText(
    val en: String,
    @SerialName("pt-BR") val ptBr: String,
) {
    fun forLocale(languageTag: String): String {
        val lang = languageTag.lowercase()
        return when {
            lang.startsWith("en") -> en
            else -> ptBr
        }
    }
}

@Serializable
data class AutoFlowTemplateAuthor(
    val name: String,
    val github: String? = null,
) {
    fun creditLine(): String {
        val handle = github?.trim()?.trimStart('@').orEmpty()
        return if (handle.isEmpty()) name else "$name (@$handle)"
    }
}

@Serializable
data class AutoFlowTemplateDefinition(
    @SerialName("wake_phrase") val wakePhrase: String? = null,
    @SerialName("source_filter") val sourceFilter: String = "all",
    @SerialName("duration_min_seconds") val durationMinSeconds: Long = 0,
    @SerialName("duration_max_seconds") val durationMaxSeconds: Long = 10_800,
    @SerialName("template_id") val templateId: String,
    @SerialName("auto_label_voice") val autoLabelVoice: Boolean = true,
    val language: String = "auto",
    val notify: Boolean = true,
    @SerialName("post_action") val postAction: String? = null,
) {
    fun normalizedPostAction(): String? {
        val v = postAction?.trim().orEmpty()
        return v.ifEmpty { null }
    }

    /** Setup the user must complete before this post-action can run. */
    fun requiredSetup(): AutoFlowTemplateSetup? = when (AutoFlowPostAction.fromStored(normalizedPostAction())) {
        AutoFlowPostAction.FolderExport -> AutoFlowTemplateSetup.ExportFolder
        AutoFlowPostAction.Webhook -> AutoFlowTemplateSetup.WebhookUrl
        AutoFlowPostAction.None,
        AutoFlowPostAction.ShareMarkdown,
        AutoFlowPostAction.CalendarInsert,
        -> null
    }
}

enum class AutoFlowTemplateSetup {
    ExportFolder,
    WebhookUrl,
}

@Serializable
data class AutoFlowTemplate(
    @SerialName("schema_version") val schemaVersion: Int,
    val id: String,
    val category: String,
    val icon: String,
    val name: LocalizedText,
    val description: LocalizedText,
    val preview: LocalizedText,
    val author: AutoFlowTemplateAuthor? = null,
    val definition: AutoFlowTemplateDefinition,
)

object AutoFlowTemplateCategories {
    val Order: List<String> = listOf(
        "meetings",
        "sales",
        "interviews",
        "medical",
        "legal",
        "education",
        "personal",
        "productivity",
        "research",
        "content",
        "real_estate",
        "finance",
        "it",
        "devices",
    )

    fun isKnown(category: String): Boolean = category in Order
}
