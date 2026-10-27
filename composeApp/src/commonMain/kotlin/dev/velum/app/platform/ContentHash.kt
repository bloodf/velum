package dev.velum.app.platform

/** Streaming SHA-256 of a file for import dedupe. */
expect fun sha256File(absolutePath: String): String

expect fun sha256Bytes(bytes: ByteArray): String
