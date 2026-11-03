package dev.velum.core.sync

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Hybrid logical clock timestamp. Totally ordered by wall time, then counter, then node id, so two
 * devices that never talk still agree on which of two writes wins.
 *
 * [node] is a random per-install id, never a hardware or account identifier: it travels with every
 * write and is the final tie-break.
 */
data class Hlc(val wallMs: Long, val counter: Int, val node: String) : Comparable<Hlc> {
    override fun compareTo(other: Hlc): Int {
        wallMs.compareTo(other.wallMs).let { if (it != 0) return it }
        counter.compareTo(other.counter).let { if (it != 0) return it }
        return node.compareTo(other.node)
    }
}

const val DEFAULT_MAX_DRIFT_MS: Long = 24L * 60 * 60 * 1000

/**
 * One device's clock. [now] is injected so tests control wall time and skew.
 *
 * - [seed]: the last timestamp persisted before shutdown. Without it a restart with a stepped-back
 *   system clock would issue timestamps below writes already made.
 * - [maxDriftMs]: a remote timestamp further ahead of [now] than this is rejected, so a bad or hostile
 *   peer cannot pin the clock in the far future.
 * Safe to call from several threads: updates are compare-and-set, so no two calls get the same timestamp.
 */
@OptIn(ExperimentalAtomicApi::class)
class HlcClock(
    private val node: String,
    seed: Hlc? = null,
    private val maxDriftMs: Long = DEFAULT_MAX_DRIFT_MS,
    private val now: () -> Long,
) {
    init { require(seed == null || seed.node == node) { "seed belongs to another node" } }

    private val last = AtomicReference(seed ?: Hlc(0L, 0, node))

    /** The newest timestamp issued or seen; persist it and pass it back as [seed] after a restart. */
    fun current(): Hlc = last.load()

    /** Timestamp for a local write; strictly greater than every timestamp this clock has issued or seen. */
    fun send(): Hlc = tick(null)

    /**
     * Folds in a timestamp from the other device so the next local write sorts after it, and returns the
     * new local timestamp. Returns null, changing nothing, when [remote] is more than [maxDriftMs] ahead of now.
     */
    fun receive(remote: Hlc): Hlc? =
        if (remote.wallMs - now() > maxDriftMs) null else tick(remote)

    private fun tick(remote: Hlc?): Hlc {
        while (true) {
            val cur = last.load()
            val next = next(cur, remote, now())
            if (last.compareAndSet(cur, next)) return next
        }
    }

    private fun next(cur: Hlc, remote: Hlc?, nowMs: Long): Hlc {
        val wall = maxOf(nowMs, cur.wallMs, remote?.wallMs ?: Long.MIN_VALUE)
        var base = -1
        if (wall == cur.wallMs) base = maxOf(base, cur.counter)
        if (remote != null && wall == remote.wallMs) base = maxOf(base, remote.counter)
        // A full counter carries into the wall clock instead of wrapping negative.
        return if (base == Int.MAX_VALUE) Hlc(wall + 1, 0, node) else Hlc(wall, base + 1, node)
    }
}
