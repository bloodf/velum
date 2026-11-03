package dev.velum.protocol

/**
 * ChaCha20-Poly1305 AEAD (RFC 8439 section 2.8) in plain Kotlin, built on [ChaCha20].
 *
 * Android's `ChaCha20/Poly1305/NoPadding` cipher is documented from API 28 only, and minSdk is 26, so the BLE
 * session cipher falls back to this when the platform has no such cipher (#226). Messages here are small BLE/Wi-Fi
 * frames, so the whole MAC input is built in one array.
 */
object ChaCha20Poly1305 {
    const val TAG_BYTES = 16

    /** Returns ciphertext followed by the 16-byte tag. */
    fun seal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray {
        val ciphertext = ChaCha20.xor(key, nonce, 1, plaintext)
        return ciphertext + tag(key, nonce, aad, ciphertext)
    }

    /** Returns the plaintext, or null when [ciphertextAndTag] is too short or the tag does not verify. */
    fun open(key: ByteArray, nonce: ByteArray, aad: ByteArray, ciphertextAndTag: ByteArray): ByteArray? {
        if (ciphertextAndTag.size < TAG_BYTES) return null
        val ciphertext = ciphertextAndTag.copyOfRange(0, ciphertextAndTag.size - TAG_BYTES)
        val expected = tag(key, nonce, aad, ciphertext)
        var diff = 0
        for (i in 0 until TAG_BYTES) {
            diff = diff or (expected[i].toInt() xor ciphertextAndTag[ciphertext.size + i].toInt())
        }
        if (diff != 0) return null
        return ChaCha20.xor(key, nonce, 1, ciphertext)
    }

    private fun tag(key: ByteArray, nonce: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray {
        val polyKey = ChaCha20.xor(key, nonce, 0, ByteArray(32))
        val aadPad = (16 - aad.size % 16) % 16
        val ctPad = (16 - ciphertext.size % 16) % 16
        val message = ByteArray(aad.size + aadPad + ciphertext.size + ctPad + 16)
        aad.copyInto(message, 0)
        ciphertext.copyInto(message, aad.size + aadPad)
        val lengths = aad.size + aadPad + ciphertext.size + ctPad
        writeLongLe(message, lengths, aad.size.toLong())
        writeLongLe(message, lengths + 8, ciphertext.size.toLong())
        return poly1305(polyKey, message)
    }

    private fun writeLongLe(out: ByteArray, at: Int, value: Long) {
        for (i in 0 until 8) out[at + i] = (value ushr (8 * i)).toByte()
    }

    private fun readIntLe(b: ByteArray, at: Int): Long =
        ((b[at].toLong() and 0xFF) or
            ((b[at + 1].toLong() and 0xFF) shl 8) or
            ((b[at + 2].toLong() and 0xFF) shl 16) or
            ((b[at + 3].toLong() and 0xFF) shl 24))

    /** Poly1305 over 26-bit limbs (poly1305-donna layout); every message here is already padded to 16 bytes. */
    private fun poly1305(key: ByteArray, message: ByteArray): ByteArray {
        val m26 = 0x3ffffffL
        val t0 = readIntLe(key, 0)
        val t1 = readIntLe(key, 4)
        val t2 = readIntLe(key, 8)
        val t3 = readIntLe(key, 12)
        val r0 = t0 and 0x3ffffff
        val r1 = ((t0 ushr 26) or (t1 shl 6)) and 0x3ffff03
        val r2 = ((t1 ushr 20) or (t2 shl 12)) and 0x3ffc0ff
        val r3 = ((t2 ushr 14) or (t3 shl 18)) and 0x3f03fff
        val r4 = (t3 ushr 8) and 0x00fffff
        val s1 = r1 * 5
        val s2 = r2 * 5
        val s3 = r3 * 5
        val s4 = r4 * 5
        var h0 = 0L
        var h1 = 0L
        var h2 = 0L
        var h3 = 0L
        var h4 = 0L

        var at = 0
        while (at < message.size) {
            val b0 = readIntLe(message, at)
            val b1 = readIntLe(message, at + 4)
            val b2 = readIntLe(message, at + 8)
            val b3 = readIntLe(message, at + 12)
            h0 += b0 and m26
            h1 += ((b0 ushr 26) or (b1 shl 6)) and m26
            h2 += ((b1 ushr 20) or (b2 shl 12)) and m26
            h3 += ((b2 ushr 14) or (b3 shl 18)) and m26
            h4 += (b3 ushr 8) or (1L shl 24)

            val d0 = h0 * r0 + h1 * s4 + h2 * s3 + h3 * s2 + h4 * s1
            var d1 = h0 * r1 + h1 * r0 + h2 * s4 + h3 * s3 + h4 * s2
            var d2 = h0 * r2 + h1 * r1 + h2 * r0 + h3 * s4 + h4 * s3
            var d3 = h0 * r3 + h1 * r2 + h2 * r1 + h3 * r0 + h4 * s4
            var d4 = h0 * r4 + h1 * r3 + h2 * r2 + h3 * r1 + h4 * r0

            var c = d0 ushr 26
            h0 = d0 and m26
            d1 += c
            c = d1 ushr 26
            h1 = d1 and m26
            d2 += c
            c = d2 ushr 26
            h2 = d2 and m26
            d3 += c
            c = d3 ushr 26
            h3 = d3 and m26
            d4 += c
            c = d4 ushr 26
            h4 = d4 and m26
            h0 += c * 5
            c = h0 ushr 26
            h0 = h0 and m26
            h1 += c
            at += 16
        }

        var c = h1 ushr 26
        h1 = h1 and m26
        h2 += c
        c = h2 ushr 26
        h2 = h2 and m26
        h3 += c
        c = h3 ushr 26
        h3 = h3 and m26
        h4 += c
        c = h4 ushr 26
        h4 = h4 and m26
        h0 += c * 5
        c = h0 ushr 26
        h0 = h0 and m26
        h1 += c

        var g0 = h0 + 5
        c = g0 ushr 26
        g0 = g0 and m26
        var g1 = h1 + c
        c = g1 ushr 26
        g1 = g1 and m26
        var g2 = h2 + c
        c = g2 ushr 26
        g2 = g2 and m26
        var g3 = h3 + c
        c = g3 ushr 26
        g3 = g3 and m26
        val g4 = h4 + c - (1L shl 26)

        val useG = (g4 ushr 63) - 1 // all ones when h >= p (g4 >= 0), else 0
        val keepH = useG.inv()
        h0 = (h0 and keepH) or (g0 and useG)
        h1 = (h1 and keepH) or (g1 and useG)
        h2 = (h2 and keepH) or (g2 and useG)
        h3 = (h3 and keepH) or (g3 and useG)
        h4 = (h4 and keepH) or ((g4 and m26) and useG)

        val w0 = (h0 or (h1 shl 26)) and 0xffffffffL
        val w1 = ((h1 ushr 6) or (h2 shl 20)) and 0xffffffffL
        val w2 = ((h2 ushr 12) or (h3 shl 14)) and 0xffffffffL
        val w3 = ((h3 ushr 18) or (h4 shl 8)) and 0xffffffffL

        var f = w0 + readIntLe(key, 16)
        val out = ByteArray(16)
        writeIntLe(out, 0, f)
        f = w1 + readIntLe(key, 20) + (f ushr 32)
        writeIntLe(out, 4, f)
        f = w2 + readIntLe(key, 24) + (f ushr 32)
        writeIntLe(out, 8, f)
        f = w3 + readIntLe(key, 28) + (f ushr 32)
        writeIntLe(out, 12, f)
        return out
    }

    private fun writeIntLe(out: ByteArray, at: Int, value: Long) {
        for (i in 0 until 4) out[at + i] = (value ushr (8 * i)).toByte()
    }
}
