package dev.velum.ble.wifi

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Counts the frames [server] has delivered, so a fake device can close only after they are through.
 *
 * Java-WebSocket's server reads on a selector thread and decodes on a worker thread. If it reads the end of the
 * connection before the worker has decoded what came before, it closes with 1006 and drops those frames. A device
 * that closes right after sending can lose its last frames that way (#349), which on CI's Linux made these tests
 * fail at random. Closing after delivery keeps the tests about Velum's behaviour, not that race.
 */
internal class DeliveredFrames(server: WifiTransferServer, scope: CoroutineScope) {
    private val count = MutableStateFlow(0)
    private val job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        server.incomingEncryptedFrames().collect { if (it !== WifiTransferServer.PEER_CLOSED_FRAME) count.update { n -> n + 1 } }
    }

    suspend fun await(frames: Int) {
        count.first { it >= frames }
    }

    fun stop() = job.cancel()
}
