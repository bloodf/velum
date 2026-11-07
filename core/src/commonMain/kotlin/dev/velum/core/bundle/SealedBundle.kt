package dev.velum.core.bundle

import dev.velum.protocol.ChaCha20Poly1305

/** Wrong passphrase, tampered or truncated bundle, or any malformed input. Deliberately not distinguishable. */
class BundleUnreadableException : IllegalArgumentException("The bundle could not be opened: wrong passphrase or damaged file.")

/**
 * Passphrase-sealed bundle container (#71): PBKDF2-HMAC-SHA256 key derivation and the RFC 8439 ChaCha20-Poly1305 AEAD
 * from `:plaud-protocol` ([ChaCha20Poly1305], which `:core` already depends on; nothing was moved).
 *
 * Wire format (big-endian), version 1:
 * ```
 * "VLMB" | version=0x01 | kdf=0x01 | iterations u32 | salt 16 | nonce 12 | ciphertext | tag 16
 * ```
 * - KDF 0x01: PBKDF2-HMAC-SHA256 ([pbkdf2HmacSha256], platform implementation), 16-byte random salt, [DEFAULT_ITERATIONS]
 *   = 600,000 (OWASP 2023 figure for this PRF), 32-byte output used directly as the AEAD key. The count is in the header;
 *   [open] accepts only [MIN_ITERATIONS]..[MAX_ITERATIONS] and checks that BEFORE any key derivation, so a tampered
 *   header can neither force a huge KDF run nor a trivially weak one.
 * - AEAD: ChaCha20-Poly1305 with a 12-byte random nonce; the whole header (magic, version, kdf, iterations, salt, nonce)
 *   is the associated data, so any header change fails authentication. The key is fresh per bundle (fresh salt).
 *
 * Threading: [seal] and [open] run the KDF (about a second on a fast JVM, longer on a phone) and must be called off the
 * main thread. Derived keys are zeroed after use. Passphrase strength and any minimum length are a UI decision; this code
 * only requires a non-empty passphrase of valid Unicode. The passphrase is normalized to NFC on every platform, so the
 * same typed text opens the same bundle everywhere; lone surrogates are rejected.
 *
 * Size: sealed bundles above [MAX_BUNDLE_BYTES] are refused before any KDF or buffer work, before a large file with a valid header can trigger key derivation or decryption.
 *
 * Randomness: [seal] uses the platform CSPRNG ([secureRandomBytes]); the internal overload exists for tests only.
 * Bluetooth transport and key pairing are out of scope; this only seals and opens bytes.
 */
object SealedBundle {
    const val VERSION: Int = 1
    const val DEFAULT_ITERATIONS: Int = 600_000
    const val MIN_ITERATIONS: Int = 210_000
    const val MAX_ITERATIONS: Int = 2_000_000

    /** Largest sealed bundle accepted or produced: 64 MiB (audio is not carried in the bundle). */
    const val MAX_BUNDLE_BYTES: Int = 64 * 1024 * 1024
    private const val KDF_PBKDF2_SHA256 = 1
    private const val SALT = 16
    private const val NONCE = 12
    private const val HEADER = 4 + 1 + 1 + 4 + SALT + NONCE
    private val MAGIC = byteArrayOf('V'.code.toByte(), 'L'.code.toByte(), 'M'.code.toByte(), 'B'.code.toByte())

    /** Seals with the platform CSPRNG. Call off the main thread. */
    fun seal(passphrase: String, plaintext: ByteArray, iterations: Int = DEFAULT_ITERATIONS): ByteArray =
        seal(passphrase, plaintext, ::secureRandomBytes, iterations)

    internal fun seal(
        passphrase: String,
        plaintext: ByteArray,
        randomBytes: (Int) -> ByteArray,
        iterations: Int = DEFAULT_ITERATIONS,
    ): ByteArray {
        require(iterations in MIN_ITERATIONS..MAX_ITERATIONS) { "iterations must be $MIN_ITERATIONS..$MAX_ITERATIONS" }
        require(plaintext.size <= MAX_BUNDLE_BYTES - HEADER - ChaCha20Poly1305.TAG_BYTES) { "bundle too large" }
        val pass = validPassphrase(passphrase)
        val salt = randomBytes(SALT).also { require(it.size == SALT) { "salt must be $SALT bytes" } }
        val nonce = randomBytes(NONCE).also { require(it.size == NONCE) { "nonce must be $NONCE bytes" } }
        val header = MAGIC + byteArrayOf(VERSION.toByte(), KDF_PBKDF2_SHA256.toByte()) + u32(iterations) + salt + nonce
        val key = pbkdf2HmacSha256(pass, salt, iterations, 32)
        try {
            return header + ChaCha20Poly1305.seal(key, nonce, header, plaintext)
        } finally {
            key.fill(0)
        }
    }

    /** Returns the plaintext, or throws [BundleUnreadableException] for any failure. */
    fun open(passphrase: String, sealed: ByteArray): ByteArray {
        if (sealed.size > MAX_BUNDLE_BYTES) throw BundleUnreadableException() // before any other work
        if (sealed.size < HEADER + ChaCha20Poly1305.TAG_BYTES) throw BundleUnreadableException()
        val pass = runCatching { validPassphrase(passphrase) }.getOrElse { throw BundleUnreadableException() }
        if (!sealed.copyOfRange(0, 4).contentEquals(MAGIC)) throw BundleUnreadableException()
        if (sealed[4].toInt() != VERSION || sealed[5].toInt() != KDF_PBKDF2_SHA256) throw BundleUnreadableException()
        val iterations = readU32(sealed, 6)
        if (iterations !in MIN_ITERATIONS..MAX_ITERATIONS) throw BundleUnreadableException() // before any KDF work
        val header = sealed.copyOfRange(0, HEADER)
        val salt = sealed.copyOfRange(10, 10 + SALT)
        val nonce = sealed.copyOfRange(10 + SALT, HEADER)
        val key = pbkdf2HmacSha256(pass, salt, iterations, 32)
        try {
            return ChaCha20Poly1305.open(key, nonce, header, sealed, HEADER, sealed.size - HEADER) ?: throw BundleUnreadableException()
        } finally {
            key.fill(0)
        }
    }

    /** Non-empty, no lone surrogates, then NFC-normalized. */
    private fun validPassphrase(passphrase: String): String {
        require(passphrase.isNotEmpty()) { "passphrase must not be empty" }
        var i = 0
        while (i < passphrase.length) {
            val c = passphrase[i]
            when {
                c.isHighSurrogate() -> {
                    require(i + 1 < passphrase.length && passphrase[i + 1].isLowSurrogate()) { "passphrase has a lone surrogate" }
                    i++
                }
                c.isLowSurrogate() -> throw IllegalArgumentException("passphrase has a lone surrogate")
            }
            i++
        }
        return normalizeNfc(passphrase)
    }

    private fun u32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun readU32(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or
            ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)
}
