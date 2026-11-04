package dev.velum.app.ui.integrations

/** Interprets [WebhookDispatcher.ping] / [WebhookDispatcher.dispatch] status strings. */
object WebhookTestResult {
    fun isHttpSuccess(detail: String): Boolean {
        if (!detail.startsWith("HTTP ")) return false
        val code = detail.removePrefix("HTTP ").takeWhile { it.isDigit() }.toIntOrNull()
            ?: return false
        return code in 200..299
    }

    /** The failure text to show: `HTTP <n>` as is, a failure code (WebhookFailure) as localized copy. */
    fun failureText(detail: String, strings: dev.velum.app.i18n.Strings): String = when (detail) {
        "timeout" -> strings.added.webhookFailedTimeout
        "unresolved_host" -> strings.added.webhookFailedUnresolvedHost
        "tls" -> strings.added.webhookFailedTls
        "connection" -> strings.added.webhookFailedConnection
        else -> detail
    }
}
