package dev.velum.ai.engine

import kotlin.test.Test
import kotlin.test.assertFailsWith

class StreamingTranscriberFactoryJvmTest {
    @Test
    fun jvmFactoryNeverHandsOutAFake() {
        assertFailsWith<UnsupportedOperationException> { createStreamingTranscriber() }
    }
}
