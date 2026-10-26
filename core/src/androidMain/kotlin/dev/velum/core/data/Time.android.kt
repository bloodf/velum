package dev.velum.core.data

actual fun epochSecondsNow(): Long = System.currentTimeMillis() / 1000
