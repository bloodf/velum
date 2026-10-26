package dev.velum.ai.local

expect fun sha256Hex(bytes: ByteArray): String

/** Incremental SHA-256 for streaming downloads without buffering the whole file. */
expect fun createSha256Digest(): Sha256Digest

interface Sha256Digest {
    fun update(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size)
    fun digestHex(): String
}
