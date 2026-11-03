package dev.velum.ble.wifi

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

/**
 * Counts the frames [server] has delivered, so a fake device can close only after they are through.
 *
 * Java-WebSocket's server reads on a selector thread and decodes on a worker thread. If it reads the end of the
 * connection before the worker has decoded what came before, it closes with 1006 and drops those frames. A device
 * that closes right after sending can lose its last frames that way (#349), which on CI's Linux made these tests
 * fail at random. Closing after delivery keeps the tests about Velum's behaviour, not that race.
 *
 * It subscribes to the frames, so it counts as a consumer: don't use it in a test about a close with no consumer
 * or after the consumer has left, where it would change the path under test.
 */
internal class DeliveredFrames(server: WifiTransferServer, scope: CoroutineScope) {
    private val count = MutableStateFlow(0)
    private val job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        server.incomingEncryptedFrames().collect { if (it !== WifiTransferServer.PEER_CLOSED_FRAME) count.update { n -> n + 1 } }
    }

    suspend fun await(frames: Int) {
        withTimeoutOrNull(5.seconds) { count.first { it >= frames } }
            ?: error("only ${count.value} of $frames frames were delivered within 5 s")
    }

    fun stop() = job.cancel()
}
