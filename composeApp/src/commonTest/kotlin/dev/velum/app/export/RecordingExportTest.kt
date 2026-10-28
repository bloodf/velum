package dev.velum.app.export

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecordingExportTest {
    @Test
    fun plainText_stripsMarkdownMarkers() {
        val md = """
            # Title
            **Bold** and *italic* plus [link](https://example.com)
            - item one
            1. item two
        """.trimIndent()
        val plain = RecordingExport.plainText(md)
        assertFalse(plain.contains("**"))
        assertFalse(plain.contains("# "))
        assertFalse(plain.contains("https://"))
        assertTrue(plain.contains("Bold"))
        assertTrue(plain.contains("italic"))
        assertTrue(plain.contains("link"))
        assertTrue(plain.contains("item one"))
    }

    @Test
    fun srt_parsesSpeakerCues() {
        val transcript = """
            **Alice** · 0:05
            Hello there everyone

            **Bob** · 1:23
            Hi Alice, good morning
        """.trimIndent()
        val srt = RecordingExport.srt(transcript)
        assertTrue(srt.startsWith("1\n"))
        assertTrue(srt.contains("00:00:05,000 --> "))
        assertTrue(srt.contains("Alice: Hello there everyone"))
        assertTrue(srt.contains("2\n"))
        assertTrue(srt.contains("00:01:23,000 --> "))
        assertTrue(srt.contains("Bob: Hi Alice, good morning"))
    }

    @Test
    fun srt_supportsHourTimestamps() {
        val transcript = """
            **Narrator** · 1:02:03
            Later in the meeting
        """.trimIndent()
        val srt = RecordingExport.srt(transcript)
        assertTrue(srt.contains("01:02:03,000 --> "))
        assertTrue(srt.contains("Narrator: Later in the meeting"))
    }

    @Test
    fun parseCues_emptyWhenNoHeaders() {
        assertEquals(emptyList(), RecordingExport.parseCues("just prose without cues"))
    }

    @Test
    fun markdown_wrapsSections() {
        val md = RecordingExport.markdown("Weekly Sync", "body text", "notes")
        assertTrue(md.startsWith("# Weekly Sync"))
        assertTrue(md.contains("## Transcript"))
        assertTrue(md.contains("body text"))
        assertTrue(md.contains("## Summary"))
        assertTrue(md.contains("notes"))
    }

    @Test
    fun docxMinimal_isZipWithDocumentPart() {
        val bytes = RecordingExport.docxMinimal("**Hello** world")
        // ZIP local header signature PK\u0003\u0004
        assertEquals(0x50, bytes[0].toInt() and 0xFF)
        assertEquals(0x4B, bytes[1].toInt() and 0xFF)
        assertEquals(0x03, bytes[2].toInt() and 0xFF)
        assertEquals(0x04, bytes[3].toInt() and 0xFF)
        val asString = bytes.decodeToString()
        assertTrue(asString.contains("word/document.xml"))
        assertTrue(asString.contains("Hello world") || asString.contains("Hello"))
    }
}
