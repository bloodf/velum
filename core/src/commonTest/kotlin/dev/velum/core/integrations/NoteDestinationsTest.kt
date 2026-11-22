package dev.velum.core.integrations

import dev.velum.core.security.InMemorySecureStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class NoteDestinationsTest {
    private val hook = "https://hooks.slack.com/services/T000/B000/XXXXXXXXXXXXXXXX"

    @Test fun slack_urls_must_be_slack_webhooks() {
        assertEquals(hook, SlackWebhookUrl.normalizeAndValidate("  $hook "))
        assertEquals(
            "https://hooks.slack.com/triggers/T1/123/abc",
            SlackWebhookUrl.normalizeAndValidate("https://hooks.slack.com/triggers/T1/123/abc"),
        )
        for (bad in listOf(
            "http://hooks.slack.com/services/T/B/X",
            "https://hooks.slack.com.evil.example/services/T/B/X",
            "https://example.com/services/T/B/X",
            "https://hooks.slack.com/api/chat.postMessage",
            "",
        )) {
            assertFailsWith<IllegalArgumentException>(bad) { SlackWebhookUrl.normalizeAndValidate(bad) }
        }
    }

    @Test fun notion_database_id_comes_from_an_id_or_a_url() {
        val dashed = "0123abcd-4567-89ab-cdef-0123456789ab"
        assertEquals(dashed, NotionIds.databaseId("0123abcd456789abcdef0123456789ab"))
        assertEquals(dashed, NotionIds.databaseId(dashed.uppercase()))
        assertEquals(
            dashed,
            NotionIds.databaseId("https://www.notion.so/acme/Meeting-notes-0123abcd456789abcdef0123456789ab?v=ffffffffffffffffffffffffffffffff"),
        )
        assertFailsWith<IllegalArgumentException> { NotionIds.databaseId("https://www.notion.so/acme/Meetings") }
    }

    @Test fun notion_tokens_are_internal_integration_tokens() {
        assertEquals("ntn_abcdefghijklmnopqrstu", NotionIds.validateToken(" ntn_abcdefghijklmnopqrstu "))
        assertEquals("secret_abcdefghijklmnopqrst", NotionIds.validateToken("secret_abcdefghijklmnopqrst"))
        for (bad in listOf("sk-abcdefghijklmnopqrstuvwxyz", "ntn_short", "ntn_has space inside the token")) {
            assertFailsWith<IllegalArgumentException>(bad) { NotionIds.validateToken(bad) }
        }
    }

    @Test fun settings_are_stored_only_when_valid_and_cleared_together() = runTest {
        val secure = InMemorySecureStore()
        val slack = SlackSettingsStore(secure)
        assertFailsWith<IllegalArgumentException> { slack.save("https://example.com/services/x") }
        assertFalse(slack.isConfigured())
        slack.save(hook)
        assertEquals(hook, slack.getUrl())
        slack.clear()
        assertNull(slack.getUrl())

        val notion = NotionSettingsStore(secure)
        assertFailsWith<IllegalArgumentException> { notion.save("ntn_abcdefghijklmnopqrstu", "no id here") }
        assertFalse(notion.isConfigured(), "a failed save stores nothing")
        notion.save("ntn_abcdefghijklmnopqrstu", "0123abcd456789abcdef0123456789ab")
        assertTrue(notion.isConfigured())
        notion.clear()
        assertNull(notion.getToken())
        assertNull(notion.getDatabaseId())
    }

    @Test fun markdown_becomes_slack_mrkdwn() {
        val md = """
            ## Decisions
            - **Budget** stays at 120k & approved
            - [ ] Send the deck to <Ana>
            - [x] Book the room
            1. See [the doc](https://example.com/doc)
            Plain *emphasis* and ~~old~~ text
        """.trimIndent()
        assertEquals(
            listOf(
                "*Decisions*",
                "• *Budget* stays at 120k &amp; approved",
                "☐ Send the deck to &lt;Ana&gt;",
                "☑ Book the room",
                "1. See <https://example.com/doc|the doc>",
                "Plain _emphasis_ and ~old~ text",
            ).joinToString("\n"),
            SlackMessage.toMrkdwn(md),
        )
    }

    @Test fun slack_payload_has_header_context_and_bounded_sections() {
        val long = (1..400).joinToString("\n") { "- point number $it with some words" }
        val payload = SlackMessage.payload(
            SharedNote("Weekly sync", "2026-11-22 10:39", 3_720, listOf("Ana", "Bo"), long, "transcript never posted"),
        )
        val blocks = payload["blocks"]!!.jsonArray
        assertEquals("header", blocks[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("Weekly sync", payload["text"]!!.jsonPrimitive.content)
        val context = blocks[1].jsonObject["elements"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        assertEquals("2026-11-22 10:39 · 1h 2min · Ana, Bo", context)
        assertTrue(blocks.size <= 50)
        blocks.drop(2).forEach { b ->
            assertTrue(b.jsonObject["text"]!!.jsonObject["text"]!!.jsonPrimitive.content.length <= 3000)
        }
        assertFalse(payload.toString().contains("transcript never posted"))
    }

    private fun types(blocks: List<JsonObject>) = blocks.map { it["type"]!!.jsonPrimitive.content }
    private fun texts(block: JsonObject): JsonArray {
        val type = block["type"]!!.jsonPrimitive.content
        return block[type]!!.jsonObject["rich_text"]!!.jsonArray
    }

    @Test fun markdown_becomes_notion_blocks() {
        val blocks = NotionBlocks.fromMarkdown(
            """
            # Title
            ## Key points
            - one
            * two
            1. first
            - [ ] todo open
            - [X] todo done
            > quoted
            ---
            Line one
            line two

            ```
            code here
            ```
            #### deep heading
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                "heading_1", "heading_2", "bulleted_list_item", "bulleted_list_item", "numbered_list_item",
                "to_do", "to_do", "quote", "divider", "paragraph", "code", "heading_3",
            ),
            types(blocks),
        )
        assertEquals(false, blocks[5]["to_do"]!!.jsonObject["checked"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(true, blocks[6]["to_do"]!!.jsonObject["checked"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("Line one\nline two", texts(blocks[9])[0].jsonObject["text"]!!.jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test fun inline_markdown_becomes_notion_annotations_and_links() {
        val rich = NotionBlocks.richText("Say **hi** to *Ana*, run `make`, see [docs](https://example.com).").jsonArray
        val parts = rich.map { it.jsonObject }
        val contents = parts.map { it["text"]!!.jsonObject["content"]!!.jsonPrimitive.content }
        assertEquals(listOf("Say ", "hi", " to ", "Ana", ", run ", "make", ", see ", "docs", "."), contents)
        assertEquals("true", parts[1]["annotations"]!!.jsonObject["bold"]!!.jsonPrimitive.content)
        assertEquals("true", parts[3]["annotations"]!!.jsonObject["italic"]!!.jsonPrimitive.content)
        assertEquals("true", parts[5]["annotations"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        assertEquals("https://example.com", parts[7]["text"]!!.jsonObject["link"]!!.jsonObject["url"]!!.jsonPrimitive.content)
        assertNull(parts[0]["annotations"])
    }

    @Test fun long_text_is_split_under_the_notion_limit_without_losing_words() {
        val words = (1..1500).joinToString(" ") { "word$it" }
        val blocks = NotionBlocks.fromMarkdown(words)
        assertTrue(blocks.size > 1)
        val rebuilt = blocks.flatMap { texts(it) }.map { it.jsonObject["text"]!!.jsonObject["content"]!!.jsonPrimitive.content }
        rebuilt.forEach { assertTrue(it.length <= NotionBlocks.TEXT_MAX) }
        assertEquals(words.split(' '), rebuilt.joinToString(" ").split(' '))
    }

    @Test fun page_request_sets_title_date_and_at_most_100_children() {
        val children = (1..150).map { NotionBlocks.heading2("h$it") }
        val req = NotionBlocks.createPageRequest("db-id", "Name", "Weekly sync", "When", "2026-11-22T13:39:00Z", children)
        assertEquals("db-id", req["parent"]!!.jsonObject["database_id"]!!.jsonPrimitive.content)
        val props = req["properties"]!!.jsonObject
        assertEquals(
            "Weekly sync",
            props["Name"]!!.jsonObject["title"]!!.jsonArray[0].jsonObject["text"]!!.jsonObject["content"]!!.jsonPrimitive.content,
        )
        assertEquals("2026-11-22T13:39:00Z", props["When"]!!.jsonObject["date"]!!.jsonObject["start"]!!.jsonPrimitive.content)
        assertEquals(100, req["children"]!!.jsonArray.size)
        assertNull(NotionBlocks.createPageRequest("d", "Name", "t", null, null, emptyList())["properties"]!!.jsonObject["When"])
    }

    @Test fun note_blocks_put_summary_then_transcript_under_headings() {
        val blocks = NotionBlocks.noteBlocks(
            SharedNote("t", null, 0, emptyList(), "- decided", "**Speaker 1** · 0:00\nhello"),
            summaryHeading = "Resumo",
            transcriptHeading = "Transcrição",
        )
        assertEquals(listOf("heading_2", "bulleted_list_item", "heading_2", "paragraph"), types(blocks))
        assertEquals("Transcrição", texts(blocks[2])[0].jsonObject["text"]!!.jsonObject["content"]!!.jsonPrimitive.content)
    }
}
