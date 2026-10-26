package dev.velum.protocol

/**
 * On-device `PLAUD.AI` container (512-byte header + encrypted body).
 *
 * Decryption needs the user's RSA private key and a ChaCha20 (RFC 8439, no
 * Poly1305) implementation — provided via [FileDecryptor].
 */
object PlaudFile {
    val MAGIC = "PLAUD.AI".encodeToByteArray()
    const val HEADER_SIZE: Int = 512

    const val OFF_MAGIC = 0
    const val OFF_VERSION = 8
    const val OFF_HEADER_SIZE = 10
    const val OFF_CRC = 12
    const val OFF_USER_ID = 16
    const val OFF_FILE_TYPE = 48
    const val OFF_CHANNEL = 50
    const val OFF_ENCRYPT_TYPE = 52
    const val OFF_DURATION = 54
    const val OFF_UUID = 58
    const val OFF_CHACHA_COUNTER = 128
    const val OFF_CHACHA_NONCE = 132
    const val OFF_SEGMENT_SIZE = 144
    const val OFF_KEY_CIPHER = 256
    const val KEY_CIPHER_LEN = 256
}

data class PlaudFileHeader(
    val version: Int,
    val headerSize: Int,
    val crc: Long,
    val userId: ByteArray,
    val fileType: Int,
    val channel: Int,
    val encryptType: Int,
    val durationSeconds: Long,
    val fileUuid: ByteArray,
    val chachaCounter: Long,
    val chachaNonce: ByteArray,
    val segmentSize: Long,
    val keyCipher: ByteArray,
) {
    val isRsaWrapped: Boolean get() = version == 1
    val isEcdhWrapped: Boolean get() = version == 2

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PlaudFileHeader) return false
        return version == other.version &&
            headerSize == other.headerSize &&
            crc == other.crc &&
            userId.contentEquals(other.userId) &&
            fileType == other.fileType &&
            channel == other.channel &&
            encryptType == other.encryptType &&
            durationSeconds == other.durationSeconds &&
            fileUuid.contentEquals(other.fileUuid) &&
            chachaCounter == other.chachaCounter &&
            chachaNonce.contentEquals(other.chachaNonce) &&
            segmentSize == other.segmentSize &&
            keyCipher.contentEquals(other.keyCipher)
    }

    override fun hashCode(): Int {
        var result = version
        result = 31 * result + headerSize
        result = 31 * result + crc.hashCode()
        result = 31 * result + userId.contentHashCode()
        result = 31 * result + fileType
        result = 31 * result + channel
        result = 31 * result + encryptType
        result = 31 * result + durationSeconds.hashCode()
        result = 31 * result + fileUuid.contentHashCode()
        result = 31 * result + chachaCounter.hashCode()
        result = 31 * result + chachaNonce.contentHashCode()
        result = 31 * result + segmentSize.hashCode()
        result = 31 * result + keyCipher.contentHashCode()
        return result
    }

    companion object {
        fun parse(file: ByteArray): PlaudFileHeader {
            require(file.size >= PlaudFile.HEADER_SIZE) {
                "File shorter than header (${file.size})"
            }
            require(
                file.copyOfRange(0, PlaudFile.MAGIC.size).contentEquals(PlaudFile.MAGIC),
            ) { "Missing PLAUD.AI magic" }
            return PlaudFileHeader(
                version = Bytes.readU16Le(file, PlaudFile.OFF_VERSION),
                headerSize = Bytes.readU16Le(file, PlaudFile.OFF_HEADER_SIZE),
                crc = Bytes.readU32Le(file, PlaudFile.OFF_CRC),
                userId = file.copyOfRange(PlaudFile.OFF_USER_ID, PlaudFile.OFF_USER_ID + 32),
                fileType = Bytes.readU16Le(file, PlaudFile.OFF_FILE_TYPE),
                channel = Bytes.readU16Le(file, PlaudFile.OFF_CHANNEL),
                encryptType = Bytes.readU16Le(file, PlaudFile.OFF_ENCRYPT_TYPE),
                durationSeconds = Bytes.readU32Le(file, PlaudFile.OFF_DURATION),
                fileUuid = file.copyOfRange(PlaudFile.OFF_UUID, PlaudFile.OFF_UUID + 16),
                chachaCounter = Bytes.readU32Le(file, PlaudFile.OFF_CHACHA_COUNTER),
                chachaNonce = file.copyOfRange(
                    PlaudFile.OFF_CHACHA_NONCE,
                    PlaudFile.OFF_CHACHA_NONCE + 12,
                ),
                segmentSize = Bytes.readU32Le(file, PlaudFile.OFF_SEGMENT_SIZE),
                keyCipher = file.copyOfRange(
                    PlaudFile.OFF_KEY_CIPHER,
                    PlaudFile.OFF_KEY_CIPHER + PlaudFile.KEY_CIPHER_LEN,
                ),
            )
        }
    }
}

/**
 * Decrypts a `PLAUD.AI` body to Ogg Opus (or raw Opus frames when segment size > 0).
 * Platform crypto is injected so this module stays free of JCA/CryptoKit.
 */
interface FileDecryptor {
    /**
     * RSA-decrypt the content key (PKCS#1 v1.5). If the plaintext is longer
     * than 32 bytes, use the last 32 bytes (official app behaviour).
     */
    fun unwrapContentKey(privateKeyPem: String, keyCipher: ByteArray): ByteArray

    /**
     * Plain ChaCha20 (RFC 8439, no Poly1305) over [ciphertext].
     * When [segmentSize] > 0, each segment is decrypted independently with
     * block counter reset to 0 (original Note).
     */
    fun chacha20Decrypt(
        key: ByteArray,
        nonce: ByteArray,
        initialCounter: Long,
        segmentSize: Long,
        ciphertext: ByteArray,
    ): ByteArray
}

object PlaudFileCrypto {
    fun decrypt(
        file: ByteArray,
        privateKeyPem: String,
        decryptor: FileDecryptor,
    ): ByteArray {
        val header = PlaudFileHeader.parse(file)
        require(header.isRsaWrapped) {
            "Only PLAUD.AI version 1 (RSA) is implemented; got version ${header.version}"
        }
        val contentKey = decryptor.unwrapContentKey(privateKeyPem, header.keyCipher)
        val key32 = if (contentKey.size > 32) {
            contentKey.copyOfRange(contentKey.size - 32, contentKey.size)
        } else {
            require(contentKey.size == 32) { "Content key must be 32 bytes" }
            contentKey
        }
        val body = file.copyOfRange(header.headerSize, file.size)
        return decryptor.chacha20Decrypt(
            key = key32,
            nonce = header.chachaNonce,
            initialCounter = header.chachaCounter,
            segmentSize = header.segmentSize,
            ciphertext = body,
        )
    }
}
