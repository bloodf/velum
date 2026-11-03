package dev.velum.ble.wifi

/** TEMPORARY (#349) diagnostics, removed before merge. */
object ZDebugLog {
    val byPort = java.util.concurrent.ConcurrentHashMap<Int, java.util.concurrent.ConcurrentLinkedQueue<String>>()
}
