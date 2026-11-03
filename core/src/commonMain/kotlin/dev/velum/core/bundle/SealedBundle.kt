package dev.velum.core.bundle

import dev.velum.core.integrations.WebhookCrypto

/** Wrong passphrase, tampered or truncated bundle, or any malformed input. Deliberately not distinguishable. */
class BundleUnreadableException : IllegalArgumentException("The bundle could not be opened: wrong passphrase or damaged file.")

/**
 * Passphrase-sealed bundle container (#71), built only on HMAC-SHA256 ([WebhookCrypto], present on every target).
 *
 * Wire format (big-endian), version 1:
 * ```
 * "VLMB" | version=0x01 | kdf=0x01 | iterations u32 | salt 16 | nonce 16 | ciphertext | tag 32
 * ```
 * - KDF 0x01: PBKDF2-HMAC-SHA256, 16-byte random salt, [DEFAULT_ITERATIONS] (600,000, the OWASP 2023 figure for
 *   this PRF) written in the header; open accepts 1..[MAX_ITERATIONS] and the MAC binds the value.
 * - Keys: master = PBKDF2(passphrase, salt, iterations, 32 bytes); encKey = HMAC(master, "velum-bundle-enc-v1");
 *   macKey = HMAC(master, "velum-bundle-mac-v1").
 * - Cipher: HMAC-SHA256 in counter mode (keystream block i = HMAC(encKey, nonce || i as u64), XORed), a PRF
 *   stream cipher. No AES is available in common code; this needs a security review before shipping (#68).
 * - Authentication: encrypt-then-MAC. tag = HMAC(macKey, header || ciphertext), compared in constant time before
 *   any decryption. Header (magic, version, kdf, iterations, salt, nonce) is authenticated.
 *
 * Randomness is injected ([randomBytes]) so callers pass the platform CSPRNG; salt and nonce are fresh per seal.
 * Bluetooth transport and key pairing are out of scope; this file only seals and opens bytes.
 */
object SealedBundle {
    const val VERSION: Int = 1
    const val DEFAULT_ITERATIONS: Int = 600_000
    const val MAX_ITERATIONS: Int = 10_000_000
    private const val KDF_PBKDF2_SHA256 = 1
    private const val SALT = 16
    private const val NONCE = 16
    private const val TAG = 32
    private const val HEADER = 4 + 1 + 1 + 4 + SALT + NONCE
    private val MAGIC = byteArrayOf('V'.code.toByte(), 'L'.code.toByte(), 'M'.code.toByte(), 'B'.code.toByte())

    fun seal(
        passphrase: String,
        plaintext: ByteArray,
        randomBytes: (Int) -> ByteArray,
        iterations: Int = DEFAULT_ITERATIONS,
    ): ByteArray {
        require(passphrase.isNotEmpty()) { "passphrase must not be empty" }
        require(iterations in 1..MAX_ITERATIONS) { "iterations out of range" }
        val salt = randomBytes(SALT).also { require(it.size == SALT) { "salt must be $SALT bytes" } }
        val nonce = randomBytes(NONCE).also { require(it.size == NONCE) { "nonce must be $NONCE bytes" } }
        val header = MAGIC + byteArrayOf(VERSION.toByte(), KDF_PBKDF2_SHA256.toByte()) + u32(iterations) + salt + nonce
        val (encKey, macKey) = keys(passphrase, salt, iterations)
        val body = xor(plaintext, encKey, nonce)
        return header + body + hmac(macKey, header + body)
    }

    /** Returns the plaintext, or throws [BundleUnreadableException] for any failure. */
    fun open(passphrase: String, sealed: ByteArray): ByteArray {
        if (sealed.size < HEADER + TAG || !sealed.copyOfRange(0, 4).contentEquals(MAGIC)) throw BundleUnreadableException()
        if (sealed[4].toInt() != VERSION || sealed[5].toInt() != KDF_PBKDF2_SHA256) throw BundleUnreadableException()
        val iterations = readU32(sealed, 6)
        if (iterations !in 1..MAX_ITERATIONS || passphrase.isEmpty()) throw BundleUnreadableException()
        val salt = sealed.copyOfRange(10, 10 + SALT)
        val nonce = sealed.copyOfRange(10 + SALT, HEADER)
        val tagStart = sealed.size - TAG
        val (encKey, macKey) = keys(passphrase, salt, iterations)
        val expected = hmac(macKey, sealed.copyOfRange(0, tagStart))
        if (!constantTimeEquals(expected, sealed.copyOfRange(tagStart, sealed.size))) throw BundleUnreadableException()
        return xor(sealed.copyOfRange(HEADER, tagStart), encKey, nonce)
    }

    private fun keys(passphrase: String, salt: ByteArray, iterations: Int): Pair<ByteArray, ByteArray> {
        val master = pbkdf2(passphrase.encodeToByteArray(), salt, iterations)
        return hmac(master, "velum-bundle-enc-v1".encodeToByteArray()) to hmac(master, "velum-bundle-mac-v1".encodeToByteArray())
    }

    /** PBKDF2-HMAC-SHA256, one 32-byte output block (RFC 8018). */
    internal fun pbkdf2(password: ByteArray, salt: ByteArray, iterations: Int): ByteArray {
        var u = hmac(password, salt + byteArrayOf(0, 0, 0, 1))
        val out = u.copyOf()
        for (i in 1 until iterations) {
            u = hmac(password, u)
            for (k in out.indices) out[k] = (out[k].toInt() xor u[k].toInt()).toByte()
        }
        return out
    }

    private fun xor(data: ByteArray, key: ByteArray, nonce: ByteArray): ByteArray {
        val out = ByteArray(data.size)
        var block = 0L
        var pos = 0
        while (pos < data.size) {
            val stream = hmac(key, nonce + u64(block++))
            for (k in stream.indices) {
                if (pos >= data.size) break
                out[pos] = (data[pos].toInt() xor stream[k].toInt()).toByte()
                pos++
            }
        }
        return out
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray = unhex(WebhookCrypto.hmacSha256Hex(key, data))

    private fun unhex(hex: String): ByteArray = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }

    private fun u32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun u64(v: Long) = ByteArray(8) { (v ushr (56 - 8 * it)).toByte() }
    private fun readU32(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or
            ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)
}
