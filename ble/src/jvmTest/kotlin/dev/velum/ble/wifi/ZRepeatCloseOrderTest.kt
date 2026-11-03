package dev.velum.ble.wifi

import kotlin.test.Test
import kotlin.test.fail

/** TEMPORARY (#349): repeats the tests that failed on CI's Linux, to measure the rate. Removed before merge. */
class ZRepeatCloseOrderTest {
    @Test
    fun repeatTheTestsThatFailedOnCi() {
        val runs = mapOf<String, () -> Unit>(
            "aeadFailureMidTransfer" to { WifiSoftApBleFallbackTest().aeadFailureMidTransfer_fallsBackToRealBleDownload_withExactBytes() },
            "aRejectedFrameBeatsAClose" to { WifiSoftApBleFallbackTest().aRejectedFrameBeatsACloseThatFollowedIt_howeverSlowTheConsumer() },
            "completeTransferBothCiphers" to { WifiSoftApMockServerTest().completeTransfer_matchesFixtureAndCrc_bothCiphers() },
        )
        val failures = mutableMapOf<String, MutableList<String>>()
        var total = 0
        repeat(50) {
            for ((name, run) in runs) {
                total++
                runCatching(run).onFailure { failures.getOrPut(name) { mutableListOf() } += "${it::class.simpleName}: ${it.message?.take(160)}" }
            }
        }
        val summary = "close-order repeat: ${failures.values.sumOf { it.size }} of $total failed " +
            runs.keys.joinToString { "$it=${failures[it]?.size ?: 0}" } +
            failures.entries.joinToString("") { (k, v) -> "\n  $k first: ${v.first()}" }
        println(summary)
        if (failures.isNotEmpty()) fail(summary)
    }
}
