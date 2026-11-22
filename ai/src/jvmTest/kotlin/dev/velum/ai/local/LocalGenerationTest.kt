package dev.velum.ai.local

import dev.velum.ai.local.asr.awaitLocalText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LocalGenerationTest {
    @Test fun chunks_finish_without_cancelling_native_work() = runTest {
        val result = awaitLocalText(
            start = { text, done, _ -> text(" hello"); text(" world "); done() },
            cancel = { error("successful inference must not be cancelled") },
        )
        assertEquals("hello world", result)
    }

    @Test fun cancellation_drains_native_work_before_close_and_allows_another_request() = runTest {
        val events = mutableListOf<String>()
        lateinit var done: () -> Unit
        val request = async {
            try {
                awaitLocalText(
                    start = { text, finish, _ ->
                        text("discard this partial answer")
                        done = finish
                        events += "started"
                    },
                    cancel = { events += "cancel native" },
                )
            } finally {
                events += "close"
            }
        }
        runCurrent()
        // The caller can still process UI work while the native callback is pending.
        val heartbeat = async { "UI responsive" }
        assertEquals("UI responsive", heartbeat.await())
        request.cancel()
        runCurrent()
        assertEquals(listOf("started", "cancel native"), events)
        assertFalse(request.isCompleted, "native handles must remain alive until the terminal callback")
        done()
        runCurrent()
        assertTrue(request.isCancelled)
        assertFailsWith<CancellationException> { request.await() }
        assertEquals(listOf("started", "cancel native", "close"), events)
        assertEquals("next answer", awaitLocalText({ text, finish, _ -> text("next answer"); finish() }, {}))
    }

    @Test fun native_failure_propagates_without_publishing_partial_output() = runTest {
        val failure = IllegalStateException("native failure")
        val observed = assertFailsWith<IllegalStateException> {
            awaitLocalText({ text, _, fail -> text("partial"); fail(failure) }, {})
        }
        assertEquals("native failure", observed.message)
    }
}
