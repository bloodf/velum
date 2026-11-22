package dev.velum.core.autoflow

/**
 * Local post-processing after AutoFlow runs a recording.
 * No Plaud/Google server — intents and CalendarProvider only.
 */
enum class AutoFlowPostAction(val stored: String) {
    None(""),
    ShareMarkdown("share"),
    CalendarInsert("calendar_insert"),
    /** Write Markdown + YAML frontmatter into the user-selected Obsidian/local folder. */
    FolderExport("folder_export"),
    /** POST JSON to a user-configured webhook URL (HMAC optional). */
    Webhook("webhook"),
    /** Post the summary to a Slack channel through a user-configured Incoming Webhook. */
    Slack("slack"),
    /** Create a page with summary + transcript in a user-chosen Notion database. */
    Notion("notion"),
    ;

    companion object {
        fun fromStored(value: String?): AutoFlowPostAction {
            val v = value?.trim().orEmpty()
            return entries.firstOrNull { it.stored == v } ?: None
        }
    }
}
