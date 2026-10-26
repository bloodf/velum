package dev.velum.core.autoflow

/**
 * Local post-processing after AutoFlow runs a recording.
 * No Plaud/Google server — intents and CalendarProvider only.
 */
enum class AutoFlowPostAction(val stored: String) {
    None(""),
    ShareMarkdown("share"),
    CalendarInsert("calendar_insert"),
    ;

    companion object {
        fun fromStored(value: String?): AutoFlowPostAction {
            val v = value?.trim().orEmpty()
            return entries.firstOrNull { it.stored == v } ?: None
        }
    }
}
