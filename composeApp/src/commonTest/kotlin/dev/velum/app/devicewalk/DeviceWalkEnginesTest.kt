package dev.velum.app.devicewalk

import dev.velum.ai.engine.AudioInput
import dev.velum.ai.engine.LlmRequest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class DeviceWalkEnginesTest {
    @Test
    fun fakeAsr_returnsDeterministicTranscript() = runBlocking {
        val result = DeviceWalkTranscriber.transcribe(
            AudioInput(filename = "t.wav", bytes = ByteArray(32)),
            modelId = "x",
        )
        assertTrue(result.text.contains("Device walk"))
        assertTrue(result.segments.isNotEmpty())
    }

    @Test
    fun fakeLlm_emitsMarkdownSections() = runBlocking {
        val result = DeviceWalkLlm.complete(
            LlmRequest(
                systemPrompt = "## Overview\n## Key points\n",
                userPrompt = "Summarize",
            ),
            modelId = "x",
        )
        assertTrue(result.text.contains("## Overview"))
        assertTrue(result.text.contains("Device walk"))
    }
}
