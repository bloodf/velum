package dev.velum.core.bundle

/** Cryptographically secure random bytes from the platform (`SecureRandom` on JVM/Android, `SecRandomCopyBytes` on iOS). */
expect fun secureRandomBytes(count: Int): ByteArray

/** Unicode NFC (canonical composition) of [passphrase], so the same typed text derives the same key on every platform. */
expect fun normalizeNfc(passphrase: String): String
