package dev.velum.ble.wifi

/** TEMPORARY (#349): which thread got which port, to see whether concurrent tests were handed the same one. */
object ZPortLog {
    private val entries = java.util.Collections.synchronizedList(mutableListOf<Triple<Int, Long, Long>>())

    fun record(port: Int) { entries += Triple(port, Thread.currentThread().id, System.nanoTime()) }

    /** The same port handed to two different threads within 3 s of each other. */
    fun collisions(): Int = synchronized(entries) {
        entries.groupBy { it.first }.values.sumOf { uses ->
            uses.sortedBy { it.third }.zipWithNext().count { (a, b) -> a.second != b.second && b.third - a.third < 3_000_000_000L }
        }
    }

    fun handedOut(): Int = entries.size
}
