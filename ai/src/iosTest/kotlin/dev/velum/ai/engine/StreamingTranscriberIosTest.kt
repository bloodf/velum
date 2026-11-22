package dev.velum.ai.engine

import dev.velum.ai.local.asr.IosLocalInference
import dev.velum.ai.local.asr.IosLocalInferenceBridge
import dev.velum.ai.local.asr.IosLocalInferenceTask
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StreamingTranscriberIosTest {
    @Test fun windows_preserve_pcm_options_and_emit_partial_then_single_speaker_final() = runTest {
        val inputs = mutableListOf<AudioInput>()
        val fake = object : Transcriber {
            override val engineId = "fake"
            override suspend fun transcribe(input: AudioInput, modelId: String): TranscriptionResult {
                assertEquals("whisper-tiny", modelId)
                inputs += input
                return TranscriptionResult("part${inputs.size}", engineId = engineId, modelId = modelId)
            }
        }
        val streamer = IosChunkedStreamingTranscriber(fake, "whisper-tiny")
        val emitted = mutableListOf<String>()
        streamer.onPartial = { emitted += it }
        streamer.start(8_000, "pt", "names")
        val bytes = ByteArray(8_000 * 2 * 7) { (it % 127).toByte() }
        streamer.pushPcm(bytes)
        assertEquals(listOf(48_000, 48_000), inputs.map { it.bytes.size })
        val final = streamer.finish()
        assertEquals(listOf(48_000, 48_000, 16_000), inputs.map { it.bytes.size })
        assertTrue(inputs.flatMap { it.bytes.toList() }.toByteArray().contentEquals(bytes))
        assertTrue(inputs.all { it.sampleRateHz == 8_000 && it.languageHint == "pt" && it.initialPrompt == "names" })
        assertEquals(listOf("**partial** · 0:00\n", "**partial** · 0:03\npart1\n",
            "**partial** · 0:06\npart1 part2\n", "**Speaker 1** · 0:00\npart1 part2 part3\n"), emitted)
        assertEquals(emitted.last(), final)
        streamer.cancel()
    }

    @Test fun cancellation_waits_for_decode_cleanup_and_emits_no_late_text_or_tail_decode() = runTest {
        var calls = 0
        val entered = CompletableDeferred<Unit>()
        val quiescent = CompletableDeferred<Unit>()
        val streamer = IosChunkedStreamingTranscriber(object : Transcriber {
            override val engineId = "fake"
            override suspend fun transcribe(input: AudioInput, modelId: String): TranscriptionResult {
                calls++
                entered.complete(Unit)
                try { awaitCancellation() } finally { quiescent.complete(Unit) }
            }
        }, "whisper-tiny")
        val emitted = mutableListOf<String>()
        streamer.onPartial = { emitted += it }
        streamer.start(8_000, null, null)
        val push = async(start = CoroutineStart.UNDISPATCHED) { streamer.pushPcm(ByteArray(48_002)) }
        entered.await()
        push.cancelAndJoin()
        assertTrue(quiescent.isCompleted)
        streamer.cancel()
        assertEquals(1, calls)
        assertEquals(listOf("**partial** · 0:00\n"), emitted)
        assertFailsWith<IllegalStateException> { streamer.finish() }
    }

    @Test fun factory_routes_supported_pcm_to_existing_ios_bridge() = runTest {
        val previous = IosLocalInference.bridge
        IosLocalInference.bridge = object : IosLocalInferenceBridge {
            override fun start(request: String, samples: FloatArray, onText: (String) -> Unit,
                onDone: () -> Unit, onError: (String) -> Unit): IosLocalInferenceTask {
                // Fake bridge only: no native model is opened.
                assertTrue(request.contains("\"operation\":\"transcribe\""))
                assertEquals(2, samples.size)
                onText("{\"text\":\"hello\",\"language\":\"en\"}")
                onDone()
                return object : IosLocalInferenceTask { override fun cancel() = Unit }
            }
        }
        try {
            val streamer = createStreamingTranscriber("/fake/ready", "whisper-tiny")
            streamer.start(16_000)
            streamer.pushPcm(byteArrayOf(0, 0, 0, 0))
            assertEquals("**Speaker 1** · 0:00\nhello\n", streamer.finish())
            streamer.cancel()
            assertFailsWith<IllegalArgumentException> { createStreamingTranscriber("/fake", "streaming-zipformer-en-20m-mobile") }
            assertFailsWith<IllegalArgumentException> { createStreamingTranscriber("/fake", "whistle") }
            assertFailsWith<IllegalArgumentException> { createStreamingTranscriber(null, "whisper-tiny") }
        } finally { IosLocalInference.bridge = previous }
    }
    @Test fun runtime_cancellation_drains_native_terminal_without_late_emit() = runTest {
        val previous = IosLocalInference.bridge
        val entered = CompletableDeferred<Unit>()
        var cancelled = false
        IosLocalInference.bridge = object : IosLocalInferenceBridge {
            override fun start(request: String, samples: FloatArray, onText: (String) -> Unit,
                onDone: () -> Unit, onError: (String) -> Unit): IosLocalInferenceTask {
                entered.complete(Unit)
                return object : IosLocalInferenceTask {
                    override fun cancel() {
                        cancelled = true
                        onText("{\"text\":\"late\"}")
                        onDone()
                    }
                }
            }
        }
        try {
            val streamer = createStreamingTranscriber("/fake/ready", "whisper-tiny")
            val emitted = mutableListOf<String>()
            streamer.onPartial = { emitted += it }
            streamer.start(8_000)
            val worker = async { streamer.pushPcm(ByteArray(48_000)) }
            entered.await()
            worker.cancelAndJoin()
            streamer.cancel()
            assertTrue(cancelled)
            assertTrue(worker.isCancelled)
            assertEquals(listOf("**partial** · 0:00\n"), emitted)
        } finally { IosLocalInference.bridge = previous }
    }

}
