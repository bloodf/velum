package dev.velum.app.ui.integrations

/** Interprets [WebhookDispatcher.ping] / [WebhookDispatcher.dispatch] status strings. */
object WebhookTestResult {
    fun isHttpSuccess(detail: String): Boolean {
        if (!detail.startsWith("HTTP ")) return false
        val code = detail.removePrefix("HTTP ").takeWhile { it.isDigit() }.toIntOrNull()
            ?: return false
        return code in 200..299
    }
}
