package dev.velum.app.i18n

import dev.velum.ai.integrations.DestinationLabels
import dev.velum.ai.integrations.DestinationResult
import dev.velum.ai.integrations.NotionExporter

/** Text for the Slack (#61) and Notion (#59) note destinations. Lives in [MoreStrings.destinations]. */
data class NoteDestinationStrings(
    val postActionSlack: String,
    val postActionNotion: String,
    val autoFlowNeedSlack: String,
    val autoFlowNeedNotion: String,
    val slackTitle: String,
    val slackHint: String,
    val slackUrl: String,
    val slackUrlSavedHint: String,
    val slackInvalidUrl: String,
    val slackSaved: String,
    val slackTest: String,
    val slackTestMessage: String,
    val slackClear: String,
    val slackCleared: String,
    val slackClearBody: String,
    val notionTitle: String,
    val notionHint: String,
    val notionToken: String,
    val notionTokenSavedHint: String,
    val notionDatabase: String,
    val notionDatabaseHint: String,
    val notionInvalidToken: String,
    val notionInvalidDatabase: String,
    val notionSaved: String,
    val notionTest: String,
    val notionClear: String,
    val notionCleared: String,
    val notionClearBody: String,
    val postToSlack: String,
    val exportToNotion: String,
    val postToSlackConfirmTitle: String,
    val postToSlackConfirmBody: String,
    val exportToNotionConfirmTitle: String,
    val exportToNotionConfirmBody: String,
    val slackPosted: String,
    val slackFailedFormat: String,
    val slackNotConfigured: String,
    val slackTestOk: String,
    val notionExported: String,
    val notionTestOk: String,
    val notionFailedFormat: String,
    val notionNotConfigured: String,
    val notionUnauthorized: String,
    val notionNotShared: String,
    val notionNotADatabase: String,
    val slackWebhookGone: String,
    val labelSummary: String,
    val labelTranscript: String,
    val labelNoSummary: String,
) {
    val labels: DestinationLabels get() = DestinationLabels(labelSummary, labelTranscript, labelNoSummary)
}

enum class NoteDestination { Slack, Notion }

/** One line for a snackbar or the Integrations card; never includes a URL, token or response body. */
fun Strings.destinationResultText(destination: NoteDestination, result: DestinationResult, test: Boolean = false): String {
    val d = added.destinations
    return when (result) {
        is DestinationResult.Sent -> when (destination) {
            NoteDestination.Slack -> if (test) d.slackTestOk else d.slackPosted
            NoteDestination.Notion -> if (test) d.notionTestOk else d.notionExported
        }
        DestinationResult.NotConfigured -> when (destination) {
            NoteDestination.Slack -> d.slackNotConfigured
            NoteDestination.Notion -> d.notionNotConfigured
        }
        is DestinationResult.Failed -> when (destination) {
            NoteDestination.Slack -> when (result.detail) {
                // Slack answers 403/404/410 for a webhook that was revoked or whose channel is gone.
                "HTTP 403", "HTTP 404", "HTTP 410" -> d.slackWebhookGone
                else -> formatTemplate(d.slackFailedFormat, failureDetail(result.detail))
            }
            NoteDestination.Notion -> when (result.detail) {
                "HTTP 401" -> d.notionUnauthorized
                // Notion answers 404 when the database exists but is not shared with the integration.
                "HTTP 404" -> d.notionNotShared
                NotionExporter.NOT_A_DATABASE -> d.notionNotADatabase
                else -> formatTemplate(d.notionFailedFormat, failureDetail(result.detail))
            }
        }
    }
}

private fun Strings.failureDetail(detail: String): String = when (detail) {
    "timeout" -> added.webhookFailedTimeout
    "unresolved_host" -> added.webhookFailedUnresolvedHost
    "tls" -> added.webhookFailedTls
    "connection" -> added.webhookFailedConnection
    else -> detail
}

internal val PtBrDestinations = NoteDestinationStrings(
    postActionSlack = "Publicar resumo no Slack",
    postActionNotion = "Exportar para o Notion",
    autoFlowNeedSlack = "Configure o webhook do Slack em Integrações antes de usar este fluxo.",
    autoFlowNeedNotion = "Conecte um banco de dados do Notion em Integrações antes de usar este fluxo.",
    slackTitle = "Slack",
    slackHint = "Publica o resumo de uma nota em um canal. No Slack, crie um app com Incoming Webhooks, adicione um webhook ao canal e cole a URL aqui. A transcrição não é enviada.",
    slackUrl = "URL do Incoming Webhook (https://hooks.slack.com/…)",
    slackUrlSavedHint = "Um webhook está salvo com segurança e não é mostrado de novo. Cole outra URL para substituí-lo.",
    slackInvalidUrl = "Isto não parece uma URL de webhook do Slack. Ela começa com https://hooks.slack.com/.",
    slackSaved = "Webhook do Slack salvo.",
    slackTest = "Enviar mensagem de teste",
    slackTestMessage = "Velum conectado: os resumos das notas vão aparecer neste canal.",
    slackClear = "Remover webhook do Slack",
    slackCleared = "Webhook do Slack removido.",
    slackClearBody = "O Velum deixará de publicar no Slack, inclusive nos fluxos do AutoFlow que usam o Slack.",
    notionTitle = "Notion",
    notionHint = "Cria uma página com resumo e transcrição em um banco de dados do Notion. Crie uma integração interna em notion.so/my-integrations, copie o token e compartilhe o banco de dados com ela (… > Conexões).",
    notionToken = "Token da integração interna (ntn_…)",
    notionTokenSavedHint = "Um token está salvo com segurança e não é mostrado de novo. Preencha os dois campos para substituir.",
    notionDatabase = "Link ou ID do banco de dados",
    notionDatabaseHint = "Abra o banco de dados no Notion, use Copiar link e cole aqui.",
    notionInvalidToken = "Isto não parece um token de integração interna do Notion (começa com ntn_ ou secret_).",
    notionInvalidDatabase = "Não foi encontrado um ID de banco de dados neste link.",
    notionSaved = "Notion conectado.",
    notionTest = "Testar conexão",
    notionClear = "Desconectar o Notion",
    notionCleared = "Notion desconectado.",
    notionClearBody = "O Velum esquecerá o token e o banco de dados, inclusive para os fluxos do AutoFlow que exportam para o Notion.",
    postToSlack = "Publicar no Slack",
    exportToNotion = "Exportar para o Notion",
    postToSlackConfirmTitle = "Publicar resumo no Slack?",
    postToSlackConfirmBody = "O título, a data, os oradores e o resumo desta nota serão enviados ao canal do seu webhook do Slack.",
    exportToNotionConfirmTitle = "Exportar para o Notion?",
    exportToNotionConfirmBody = "O título, a data, o resumo e a transcrição desta nota serão enviados ao seu banco de dados do Notion.",
    slackPosted = "Resumo publicado no Slack.",
    slackFailedFormat = "Não foi possível publicar no Slack (%s).",
    slackNotConfigured = "O Slack não está configurado ou a nota não existe mais.",
    slackTestOk = "Mensagem de teste enviada. Confira o canal.",
    notionExported = "Nota exportada para o Notion.",
    notionTestOk = "Conectado: o banco de dados está acessível.",
    notionFailedFormat = "Não foi possível exportar para o Notion (%s).",
    notionNotConfigured = "O Notion não está conectado ou a nota não existe mais.",
    notionUnauthorized = "O Notion recusou o token. Crie um novo token e conecte de novo.",
    notionNotShared = "Banco de dados não encontrado. No Notion, compartilhe-o com a sua integração (… > Conexões).",
    notionNotADatabase = "Este link é de uma página, não de um banco de dados. Copie o link do banco de dados.",
    slackWebhookGone = "O Slack recusou o webhook: ele foi revogado ou o canal não existe mais. Crie outro webhook.",
    labelSummary = "Resumo",
    labelTranscript = "Transcrição",
    labelNoSummary = "(ainda sem resumo)",
)

internal val EnDestinations = NoteDestinationStrings(
    postActionSlack = "Post summary to Slack",
    postActionNotion = "Export to Notion",
    autoFlowNeedSlack = "Set up the Slack webhook in Integrations before using this flow.",
    autoFlowNeedNotion = "Connect a Notion database in Integrations before using this flow.",
    slackTitle = "Slack",
    slackHint = "Posts a note's summary to a channel. In Slack, create an app with Incoming Webhooks, add a webhook to the channel and paste its URL here. The transcript is not sent.",
    slackUrl = "Incoming Webhook URL (https://hooks.slack.com/…)",
    slackUrlSavedHint = "A webhook is stored securely and is not shown again. Paste another URL to replace it.",
    slackInvalidUrl = "This doesn't look like a Slack webhook URL. It starts with https://hooks.slack.com/.",
    slackSaved = "Slack webhook saved.",
    slackTest = "Send test message",
    slackTestMessage = "Velum is connected: note summaries will appear in this channel.",
    slackClear = "Remove Slack webhook",
    slackCleared = "Slack webhook removed.",
    slackClearBody = "Velum will stop posting to Slack, including from AutoFlow flows that use Slack.",
    notionTitle = "Notion",
    notionHint = "Creates a page with the summary and transcript in a Notion database. Create an internal integration at notion.so/my-integrations, copy its token, and share the database with it (… > Connections).",
    notionToken = "Internal integration token (ntn_…)",
    notionTokenSavedHint = "A token is stored securely and is not shown again. Fill in both fields to replace it.",
    notionDatabase = "Database link or ID",
    notionDatabaseHint = "Open the database in Notion, use Copy link, and paste it here.",
    notionInvalidToken = "This doesn't look like a Notion internal integration token (it starts with ntn_ or secret_).",
    notionInvalidDatabase = "No database ID was found in this link.",
    notionSaved = "Notion connected.",
    notionTest = "Test connection",
    notionClear = "Disconnect Notion",
    notionCleared = "Notion disconnected.",
    notionClearBody = "Velum will forget the token and database, including for AutoFlow flows that export to Notion.",
    postToSlack = "Post to Slack",
    exportToNotion = "Export to Notion",
    postToSlackConfirmTitle = "Post summary to Slack?",
    postToSlackConfirmBody = "This note's title, date, speakers and summary will be sent to your Slack webhook's channel.",
    exportToNotionConfirmTitle = "Export to Notion?",
    exportToNotionConfirmBody = "This note's title, date, summary and transcript will be sent to your Notion database.",
    slackPosted = "Summary posted to Slack.",
    slackFailedFormat = "Could not post to Slack (%s).",
    slackNotConfigured = "Slack is not set up, or the note no longer exists.",
    slackTestOk = "Test message sent. Check the channel.",
    notionExported = "Note exported to Notion.",
    notionTestOk = "Connected: the database is reachable.",
    notionFailedFormat = "Could not export to Notion (%s).",
    notionNotConfigured = "Notion is not connected, or the note no longer exists.",
    notionUnauthorized = "Notion rejected the token. Create a new token and connect again.",
    notionNotShared = "Database not found. In Notion, share it with your integration (… > Connections).",
    notionNotADatabase = "This link is a page, not a database. Copy the database's link.",
    slackWebhookGone = "Slack rejected the webhook: it was revoked or the channel is gone. Create a new webhook.",
    labelSummary = "Summary",
    labelTranscript = "Transcript",
    labelNoSummary = "(no summary yet)",
)
