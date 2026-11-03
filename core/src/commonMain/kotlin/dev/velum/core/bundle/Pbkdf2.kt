package dev.velum.core.bundle

/**
 * PBKDF2-HMAC-SHA256 (RFC 8018) from the platform: `javax.crypto` on JVM and Android (API 26+), CommonCrypto on iOS.
 * [password] is encoded as UTF-8 by every platform. Returns [keyBytes] bytes.
 */
expect fun pbkdf2HmacSha256(password: String, salt: ByteArray, iterations: Int, keyBytes: Int): ByteArray
