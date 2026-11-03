package dev.velum.core.sync

/**
 * Hybrid logical clock timestamp. Totally ordered by wall time, then counter, then node id, so two
 * devices that never talk still agree on which of two writes wins.
 */
data class Hlc(val wallMs: Long, val counter: Int, val node: String) : Comparable<Hlc> {
    override fun compareTo(other: Hlc): Int {
        wallMs.compareTo(other.wallMs).let { if (it != 0) return it }
        counter.compareTo(other.counter).let { if (it != 0) return it }
        return node.compareTo(other.node)
    }
}

/** One device's clock. [now] is injected so tests control wall time and skew. */
class HlcClock(private val node: String, private val now: () -> Long) {
    private var last = Hlc(0L, 0, node)

    /** Timestamp for a local write; strictly greater than every timestamp this clock has issued or seen. */
    fun send(): Hlc = advance(last.wallMs, last.counter)

    /** Folds in a timestamp from the other device, so the next local write sorts after it. */
    fun receive(remote: Hlc): Hlc =
        if (remote > last) advance(remote.wallMs, remote.counter) else send()

    private fun advance(floorMs: Long, floorCounter: Int): Hlc {
        val wall = maxOf(now(), floorMs)
        val counter = if (wall == floorMs) floorCounter + 1 else 0
        last = Hlc(wall, counter, node)
        return last
    }
}
