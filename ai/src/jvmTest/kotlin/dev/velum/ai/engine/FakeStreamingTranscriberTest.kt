package dev.velum.ai.engine

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FakeStreamingTranscriberTest {
    @Test
    fun concatenatesChunksIntoMarkdown() {
        val streamer = createStreamingTranscriber()
        assertTrue(streamer is FakeStreamingTranscriber)
        val partials = mutableListOf<String>()
        streamer.onPartial = { partials += it }
        streamer.start(16_000, "en", "Velum BLE")
        streamer.pushPcm(ByteArray(3200))
        streamer.pushPcm(ByteArray(1600))
        val final = streamer.finish()
        assertContains(final, "chunk 3200B")
        assertContains(final, "chunk 1600B")
        assertContains(final, "**final**")
        assertTrue(partials.size >= 3)
        assertContains(partials.first(), "prompt: Velum BLE")
    }

    @Test
    fun emitsPartialMarkdownOnEachPush() {
        val streamer = FakeStreamingTranscriber()
        val partials = mutableListOf<String>()
        streamer.onPartial = { partials += it }
        streamer.start(16_000, "pt", null)
        assertEquals(1, partials.size)
        assertContains(partials[0], "**partial**")

        streamer.pushPcm(ByteArray(800))
        assertEquals(2, partials.size)
        assertContains(partials[1], "chunk 800B")

        streamer.pushPcm(ByteArray(1600))
        assertEquals(3, partials.size)
        assertContains(partials[2], "chunk 800B")
        assertContains(partials[2], "chunk 1600B")

        val final = streamer.finish()
        assertEquals(4, partials.size)
        assertContains(final, "**final**")
        assertEquals(partials.last(), final)
    }
}
