package dev.velum.protocol

/**
 * Protocol-20 RSA unlock framing (pre-handshake).
 *
 * Chunks are sent without the usual `0x01` app→device prefix:
 * `[opcode u16 LE][total u8][index u8][≤100 bytes]`.
 *
 * Device keys (serial signature, RSA key pair, bind token) are supplied by the
 * user via a one-time import — Velum never ships or hardcodes them.
 */
object Unlock {
    const val MAX_CHUNK_PAYLOAD: Int = 100

    fun encodeChunks(opcode: Int, data: ByteArray): List<ByteArray> {
        ForbiddenOpcodes.requireAllowed(opcode)
        require(data.isNotEmpty()) { "Unlock payload must not be empty" }
        val total = (data.size + MAX_CHUNK_PAYLOAD - 1) / MAX_CHUNK_PAYLOAD
        require(total in 1..255) { "Payload too large for u8 total ($total)" }
        return (0 until total).map { index ->
            val start = index * MAX_CHUNK_PAYLOAD
            val end = minOf(start + MAX_CHUNK_PAYLOAD, data.size)
            val slice = data.copyOfRange(start, end)
            ByteArray(2 + 2 + slice.size).also { out ->
                Bytes.u16Le(opcode).copyInto(out, 0)
                out[2] = total.toByte()
                out[3] = index.toByte()
                slice.copyInto(out, 4)
            }
        }
    }

    fun encodeSnSignatureChunks(signature256: ByteArray): List<ByteArray> {
        require(signature256.size == 256) {
            "SN signature must be 256 bytes (RSA-2048), was ${signature256.size}"
        }
        return encodeChunks(Commands.UNLOCK_SN_SIGNATURE, signature256)
    }

    fun encodePublicKeyChunks(pemText: ByteArray): List<ByteArray> =
        encodeChunks(Commands.UNLOCK_SESSION_SECRET, pemText)

    fun parseChunk(raw: ByteArray): UnlockChunk {
        require(raw.size >= 4) { "Unlock chunk too short" }
        val opcode = Bytes.readU16Le(raw, 0)
        val total = raw[2].toInt() and 0xFF
        val index = raw[3].toInt() and 0xFF
        val data = raw.copyOfRange(4, raw.size)
        return UnlockChunk(opcode, total, index, data)
    }

    /**
     * Reassemble chunked payloads by index order. Missing indices throw.
     */
    fun reassemble(chunks: List<UnlockChunk>): ByteArray {
        require(chunks.isNotEmpty()) { "No chunks" }
        val total = chunks.first().total
        require(chunks.all { it.total == total }) { "Inconsistent total" }
        require(chunks.size == total) { "Expected $total chunks, got ${chunks.size}" }
        val ordered = chunks.sortedBy { it.index }
        require(ordered.map { it.index } == (0 until total).toList()) {
            "Missing or duplicate chunk indices"
        }
        return ordered.fold(ByteArray(0)) { acc, c -> acc + c.data }
    }
}

data class UnlockChunk(
    val opcode: Int,
    val total: Int,
    val index: Int,
    val data: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is UnlockChunk) return false
        return opcode == other.opcode &&
            total == other.total &&
            index == other.index &&
            data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = opcode
        result = 31 * result + total
        result = 31 * result + index
        result = 31 * result + data.contentHashCode()
        return result
    }
}

/**
 * Session material after a successful protocol-20 unlock.
 * Crypto primitives are injected via [SessionCipher] — this module stays pure.
 */
data class Protocol20SessionSecret(
    val key: ByteArray,
    val nonce: ByteArray,
    val aad: ByteArray,
) {
    init {
        require(key.size == 32) { "key must be 32 bytes" }
        require(nonce.size == 12) { "nonce must be 12 bytes" }
        require(aad.size == 12) { "aad must be 12 bytes" }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Protocol20SessionSecret) return false
        return key.contentEquals(other.key) &&
            nonce.contentEquals(other.nonce) &&
            aad.contentEquals(other.aad)
    }

    override fun hashCode(): Int =
        31 * (31 * key.contentHashCode() + nonce.contentHashCode()) + aad.contentHashCode()

    /** Never include key material in logs or exception text. */
    override fun toString(): String =
        "Protocol20SessionSecret(key=<redacted ${key.size} bytes>, " +
            "nonce=<redacted ${nonce.size} bytes>, aad=<redacted ${aad.size} bytes>)"
}

/**
 * Parsed layout of the RSA-decrypted unlock blob:
 * `key[32] || nonce[12] || aad[12] || chacha20poly1305(test)` where test decrypts to `"PLAUD.AI"`.
 */
object UnlockSecretLayout {
    const val KEY_LEN = 32
    const val NONCE_LEN = 12
    const val AAD_LEN = 12
    const val HEADER_LEN = KEY_LEN + NONCE_LEN + AAD_LEN
    val TEST_PLAINTEXT = "PLAUD.AI".encodeToByteArray()

    fun parseHeader(decrypted: ByteArray): Protocol20SessionSecret {
        require(decrypted.size >= HEADER_LEN) {
            "Decrypted secret too short: ${decrypted.size}"
        }
        return Protocol20SessionSecret(
            key = decrypted.copyOfRange(0, KEY_LEN),
            nonce = decrypted.copyOfRange(KEY_LEN, KEY_LEN + NONCE_LEN),
            aad = decrypted.copyOfRange(KEY_LEN + NONCE_LEN, HEADER_LEN),
        )
    }

    fun testCiphertext(decrypted: ByteArray): ByteArray {
        require(decrypted.size > HEADER_LEN) { "No test ciphertext present" }
        return decrypted.copyOfRange(HEADER_LEN, decrypted.size)
    }
}

/** Opaque holder for keys the user imports once from their Plaud account. */
data class DeviceIdentityKeys(
    val serialNumber: String,
    /** 256-byte RSA signature over the serial (from `sn-sign`). */
    val serialSignature: ByteArray,
    /** PEM-encoded RSA-2048 public key (user E2EE). */
    val publicKeyPem: String,
    /** PEM-encoded RSA-2048 private key — store only via secure storage. */
    val privateKeyPem: String,
    /** Bind token (= Plaud user_id). */
    val bindToken: String,
) {
    init {
        require(serialSignature.size == 256) {
            "serialSignature must be 256 bytes"
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DeviceIdentityKeys) return false
        return serialNumber == other.serialNumber &&
            serialSignature.contentEquals(other.serialSignature) &&
            publicKeyPem == other.publicKeyPem &&
            privateKeyPem == other.privateKeyPem &&
            bindToken == other.bindToken
    }

    override fun hashCode(): Int {
        var result = serialNumber.hashCode()
        result = 31 * result + serialSignature.contentHashCode()
        result = 31 * result + publicKeyPem.hashCode()
        result = 31 * result + privateKeyPem.hashCode()
        result = 31 * result + bindToken.hashCode()
        return result
    }

    /** Identity values are secrets — never emit them via toString / logs. */
    override fun toString(): String =
        "DeviceIdentityKeys(serialNumber=<redacted>, " +
            "serialSignature=<redacted ${serialSignature.size} bytes>, " +
            "publicKeyPem=<redacted>, privateKeyPem=<redacted>, bindToken=<redacted>)"
}

/**
 * Platform crypto for unlock + link encryption. Implementations live outside
 * this pure module (Android Keystore / CryptoKit / JCA).
 */
interface SessionCipher {
    /** RSA PKCS#1 v1.5 decrypt of the unlock secret. */
    fun rsaDecryptPkcs1(privateKeyPem: String, ciphertext: ByteArray): ByteArray

    /** RSA PKCS#1 v1.5 encrypt (device → app unlock secret, tests / fake peripheral). */
    fun rsaEncryptPkcs1(publicKeyPem: String, plaintext: ByteArray): ByteArray

    /** ChaCha20-Poly1305 open; returns plaintext or null on auth failure. */
    fun chacha20Poly1305Open(
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
        ciphertextAndTag: ByteArray,
    ): ByteArray?

    /** ChaCha20-Poly1305 seal; returns ciphertext ‖ 16-byte tag. */
    fun chacha20Poly1305Seal(
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
        plaintext: ByteArray,
    ): ByteArray

    /**
     * Encrypt a link-layer packet after protocol-20 unlock (APK 3.30.0).
     *
     * - key / nonce / aad are **fixed** for the whole session (do not mutate nonce with seq)
     * - plaintext = `seq_u32_le || packet`
     * - aad = [Protocol20SessionSecret.aad]
     *
     * App send counter starts at 2 after unlock. The same seal is used for BLE
     * writes and Wi-Fi WebSocket frames (Wi-Fi may use AES-GCM when
     * FeatureRequest bit 0x08 is set).
     */
    fun sealPacket(
        secret: Protocol20SessionSecret,
        sequence: Long,
        packet: ByteArray,
    ): ByteArray

    /**
     * Decrypt a sealed link packet.
     *
     * Uses the session's fixed nonce and verifies the plaintext leading u32
     * matches [sequence]. Returns `(sequence, packet)` or null on auth failure /
     * mismatch. Receivers drop seq ≤ last seen.
     */
    fun openPacket(
        secret: Protocol20SessionSecret,
        sequence: Long,
        ciphertextAndTag: ByteArray,
    ): Pair<Long, ByteArray>?
}
