package dev.velum.app.i18n

import dev.velum.ai.integrations.DestinationResult
import dev.velum.ai.integrations.NotionExporter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class NoteDestinationCopyTest {
    @Test fun sent_and_test_results_use_their_own_lines() {
        assertEquals(En.added.destinations.slackPosted, En.destinationResultText(NoteDestination.Slack, DestinationResult.Sent()))
        assertEquals(En.added.destinations.slackTestOk, En.destinationResultText(NoteDestination.Slack, DestinationResult.Sent(), test = true))
        assertEquals(En.added.destinations.notionExported, En.destinationResultText(NoteDestination.Notion, DestinationResult.Sent("https://notion.so/x")))
        assertEquals(PtBr.added.destinations.notionTestOk, PtBr.destinationResultText(NoteDestination.Notion, DestinationResult.Sent(), test = true))
    }

    @Test fun notion_failures_explain_the_usual_setup_mistakes() {
        val d = En.added.destinations
        assertEquals(d.notionUnauthorized, En.destinationResultText(NoteDestination.Notion, DestinationResult.Failed("HTTP 401")))
        assertEquals(d.notionNotShared, En.destinationResultText(NoteDestination.Notion, DestinationResult.Failed("HTTP 404")))
        assertEquals(d.notionNotADatabase, En.destinationResultText(NoteDestination.Notion, DestinationResult.Failed(NotionExporter.NOT_A_DATABASE)))
        assertEquals("Could not export to Notion (HTTP 500).", En.destinationResultText(NoteDestination.Notion, DestinationResult.Failed("HTTP 500")))
    }

    @Test fun slack_failures_localize_connection_codes_and_flag_a_revoked_webhook() {
        assertEquals(En.added.destinations.slackWebhookGone, En.destinationResultText(NoteDestination.Slack, DestinationResult.Failed("HTTP 404")))
        assertEquals("Could not post to Slack (timed out).", En.destinationResultText(NoteDestination.Slack, DestinationResult.Failed("timeout")))
        assertEquals(
            "Não foi possível publicar no Slack (sem conexão).",
            PtBr.destinationResultText(NoteDestination.Slack, DestinationResult.Failed("connection")),
        )
        assertEquals(En.added.destinations.slackNotConfigured, En.destinationResultText(NoteDestination.Slack, DestinationResult.NotConfigured))
    }

    @Test fun both_languages_fill_every_field_and_differ_where_they_should() {
        val en = En.added.destinations
        val pt = PtBr.added.destinations
        val enValues = en.toString().removePrefix("NoteDestinationStrings(").removeSuffix(")")
        assertFalse(Regex("=\\s*,").containsMatchIn(enValues), "blank English string")
        assertNotEquals(en.labelSummary, pt.labelSummary)
        assertEquals("Resumo", pt.labels.summary)
        assertEquals("Transcrição", pt.labels.transcript)
        assertEquals("Transcript", en.labels.transcript)
        for (format in listOf(en.slackFailedFormat, en.notionFailedFormat, pt.slackFailedFormat, pt.notionFailedFormat)) {
            assertTrue("%s" in format, format)
        }
    }
}
