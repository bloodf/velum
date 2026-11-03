package dev.velum.ble.wifi

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.fail

/** TEMPORARY (#349): repeats the tests that failed on CI's Linux, 4 threads at once, to measure the rate. Removed before merge. */
class ZRepeatCloseOrderTest {
    @Test
    fun repeatTheTestsThatFailedOnCi() {
        val runs = listOf<Triple<String, Int, () -> Unit>>(
            Triple("aeadFailureMidTransfer", 150, { WifiSoftApBleFallbackTest().aeadFailureMidTransfer_fallsBackToRealBleDownload_withExactBytes() }),
            Triple("completeTransferBothCiphers", 60, { WifiSoftApMockServerTest().completeTransfer_matchesFixtureAndCrc_bothCiphers() }),
            Triple("aRejectedFrameBeatsAClose", 8, { WifiSoftApBleFallbackTest().aRejectedFrameBeatsACloseThatFollowedIt_howeverSlowTheConsumer() }),
        )
        val failures = ConcurrentHashMap<String, MutableList<String>>()
        val total = AtomicInteger()
        val workers = List(4) {
            thread {
                for ((name, times, run) in runs) repeat(times) {
                    total.incrementAndGet()
                    runCatching(run).onFailure {
                        failures.computeIfAbsent(name) { java.util.Collections.synchronizedList(mutableListOf()) } +=
                            "${it::class.simpleName}: ${it.message?.take(160)} LOG:" + ZPortLog.lastPort()?.let { p -> ZDebugLog.byPort[p]?.joinToString(" | ") }
                    }
                }
            }
        }
        workers.forEach { it.join() }
        val summary = "close-order repeat: ports ${ZPortLog.handedOut()}, cross-thread collisions ${ZPortLog.collisions()}; ${failures.values.sumOf { it.size }} of ${total.get()} failed " +
            runs.joinToString { "${it.first}=${failures[it.first]?.size ?: 0}" } +
            failures.entries.joinToString("") { (k, v) -> v.take(3).joinToString("") { "\n  $k: $it" } }
        println(summary)
        if (failures.isNotEmpty()) fail(summary)
    }
}
