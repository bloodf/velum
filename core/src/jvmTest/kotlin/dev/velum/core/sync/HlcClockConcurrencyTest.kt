package dev.velum.core.sync

import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals

class HlcClockConcurrencyTest {
    @Test fun concurrent_send_and_receive_never_issue_the_same_timestamp() {
        val clock = HlcClock("phone", now = { 1L }) // frozen wall clock: every call races on the counter
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val results = (1..8).map { worker ->
            pool.submit<List<Hlc>> {
                start.await()
                List(2_000) {
                    if (worker % 2 == 0) clock.send() else clock.receive(Hlc(1L, it, "desktop"))!!
                }
            }
        }
        start.countDown()
        val all = results.flatMap { it.get() }
        pool.shutdown()
        assertEquals(all.size, all.toSet().size, "duplicate timestamps issued")
    }
}
