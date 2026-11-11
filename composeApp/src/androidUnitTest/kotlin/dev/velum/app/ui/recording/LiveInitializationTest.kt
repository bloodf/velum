package dev.velum.app.ui.recording

import dev.velum.ai.engine.StreamingTranscriber
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class LiveInitializationTest {
    @Test fun pending_initialization_preserves_pcm_until_the_transcriber_opens_once() = runTest {
        val initialized = CompletableDeferred<Unit>()
        val pcm = Channel<ByteArray>(2)
        pcm.send(byteArrayOf(1, 2))
        pcm.close()
        var opens = 0
        var chunks = 0
        var ready = 0
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            transcribeQueuedPcm(
                initialize = { initialized.await() },
                open = {
                    opens++
                    object : StreamingTranscriber {
                        override val engineId = "test"
                        override var onPartial: (String) -> Unit = {}
                        override fun start(sampleRateHz: Int, languageHint: String?, initialPrompt: String?) = Unit
                        override fun pushPcm(pcm16Le: ByteArray) {
                            assertEquals(listOf<Byte>(1, 2), pcm16Le.toList())
                            chunks++
                        }
                        override fun finish() = "Verified live text"
                    }
                },
                pcm = pcm,
                languageHint = null,
                onPartial = {},
                onReady = { ready++ },
                onNoTranscriber = { error("pending initialization must not cancel PCM") },
            )
        }
        assertFalse(result.isCompleted)
        assertEquals(0, opens)
        assertEquals(0, chunks)
        initialized.complete(Unit)
        assertEquals("Verified live text", result.await())
        assertEquals(1, opens)
        assertEquals(1, chunks)
        assertEquals(1, ready)
    }
}
