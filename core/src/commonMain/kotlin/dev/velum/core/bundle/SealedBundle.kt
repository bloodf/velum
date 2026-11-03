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
 * Randomness is injected ([randomBytes]) so callers pass the platform CSPRNG. Bluetooth transport and key pairing are out
 * of scope; this only seals and opens bytes.
 */
object SealedBundle {
    const val VERSION: Int = 1
    const val DEFAULT_ITERATIONS: Int = 600_000
    const val MIN_ITERATIONS: Int = 210_000
    const val MAX_ITERATIONS: Int = 2_000_000
    private const val KDF_PBKDF2_SHA256 = 1
    private const val SALT = 16
    private const val NONCE = 12
    private const val HEADER = 4 + 1 + 1 + 4 + SALT + NONCE
    private val MAGIC = byteArrayOf('V'.code.toByte(), 'L'.code.toByte(), 'M'.code.toByte(), 'B'.code.toByte())

    fun seal(
        passphrase: String,
        plaintext: ByteArray,
        randomBytes: (Int) -> ByteArray,
        iterations: Int = DEFAULT_ITERATIONS,
    ): ByteArray {
        require(passphrase.isNotEmpty()) { "passphrase must not be empty" }
        require(iterations in MIN_ITERATIONS..MAX_ITERATIONS) { "iterations must be $MIN_ITERATIONS..$MAX_ITERATIONS" }
        val salt = randomBytes(SALT).also { require(it.size == SALT) { "salt must be $SALT bytes" } }
        val nonce = randomBytes(NONCE).also { require(it.size == NONCE) { "nonce must be $NONCE bytes" } }
        val header = MAGIC + byteArrayOf(VERSION.toByte(), KDF_PBKDF2_SHA256.toByte()) + u32(iterations) + salt + nonce
        val key = pbkdf2HmacSha256(passphrase, salt, iterations, 32)
        return header + ChaCha20Poly1305.seal(key, nonce, header, plaintext)
    }

    /** Returns the plaintext, or throws [BundleUnreadableException] for any failure. */
    fun open(passphrase: String, sealed: ByteArray): ByteArray {
        if (sealed.size < HEADER + ChaCha20Poly1305.TAG_BYTES || passphrase.isEmpty()) throw BundleUnreadableException()
        if (!sealed.copyOfRange(0, 4).contentEquals(MAGIC)) throw BundleUnreadableException()
        if (sealed[4].toInt() != VERSION || sealed[5].toInt() != KDF_PBKDF2_SHA256) throw BundleUnreadableException()
        val iterations = readU32(sealed, 6)
        if (iterations !in MIN_ITERATIONS..MAX_ITERATIONS) throw BundleUnreadableException() // before any KDF work
        val header = sealed.copyOfRange(0, HEADER)
        val salt = sealed.copyOfRange(10, 10 + SALT)
        val nonce = sealed.copyOfRange(10 + SALT, HEADER)
        val key = pbkdf2HmacSha256(passphrase, salt, iterations, 32)
        return ChaCha20Poly1305.open(key, nonce, header, sealed.copyOfRange(HEADER, sealed.size)) ?: throw BundleUnreadableException()
    }

    private fun u32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun readU32(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or
            ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)
}
