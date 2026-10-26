package dev.velum.ai.local

import java.security.MessageDigest

actual fun sha256Hex(bytes: ByteArray): String = createSha256Digest().also { it.update(bytes) }.digestHex()

actual fun createSha256Digest(): Sha256Digest = JvmSha256Digest()

private class JvmSha256Digest : Sha256Digest {
    private val digest = MessageDigest.getInstance("SHA-256")

    override fun update(bytes: ByteArray, offset: Int, length: Int) {
        digest.update(bytes, offset, length)
    }

    override fun digestHex(): String =
        digest.digest().joinToString("") { b ->
            (b.toInt() and 0xff).toString(16).padStart(2, '0')
        }
}
