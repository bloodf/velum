package dev.velum.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PlaudFileTest {
    @Test
    fun parseHeader_syntheticRsaFile() {
        val file = ByteArray(PlaudFile.HEADER_SIZE + 16)
        PlaudFile.MAGIC.copyInto(file, 0)
        Bytes.u16Le(1).copyInto(file, PlaudFile.OFF_VERSION)
        Bytes.u16Le(512).copyInto(file, PlaudFile.OFF_HEADER_SIZE)
        Bytes.u32Le(0xAABBCCDDL).copyInto(file, PlaudFile.OFF_CRC)
        "userid_01234567890123456789012".encodeToByteArray().copyInto(file, PlaudFile.OFF_USER_ID)
        Bytes.u16Le(1).copyInto(file, PlaudFile.OFF_FILE_TYPE)
        Bytes.u16Le(1).copyInto(file, PlaudFile.OFF_CHANNEL)
        Bytes.u16Le(1).copyInto(file, PlaudFile.OFF_ENCRYPT_TYPE)
        Bytes.u32Le(8).copyInto(file, PlaudFile.OFF_DURATION)
        ByteArray(16) { 0x11 }.copyInto(file, PlaudFile.OFF_UUID)
        Bytes.u32Le(1).copyInto(file, PlaudFile.OFF_CHACHA_COUNTER)
        ByteArray(12) { 0x22 }.copyInto(file, PlaudFile.OFF_CHACHA_NONCE)
        Bytes.u32Le(0).copyInto(file, PlaudFile.OFF_SEGMENT_SIZE)
        ByteArray(256) { it.toByte() }.copyInto(file, PlaudFile.OFF_KEY_CIPHER)

        val header = PlaudFileHeader.parse(file)
        assertEquals(1, header.version)
        assertEquals(512, header.headerSize)
        assertEquals(0xAABBCCDDL, header.crc)
        assertEquals(8, header.durationSeconds)
        assertEquals(0, header.segmentSize)
        assertTrue(header.isRsaWrapped)
        assertEquals(256, header.keyCipher.size)
    }

    @Test
    fun parseHeader_rejectsBadMagic() {
        val file = ByteArray(PlaudFile.HEADER_SIZE) { 0 }
        assertFailsWith<IllegalArgumentException> {
            PlaudFileHeader.parse(file)
        }
    }

    @Test
    fun decrypt_usesLast32BytesOfUnwrappedKey() {
        val file = ByteArray(PlaudFile.HEADER_SIZE + 4)
        PlaudFile.MAGIC.copyInto(file, 0)
        Bytes.u16Le(1).copyInto(file, PlaudFile.OFF_VERSION)
        Bytes.u16Le(512).copyInto(file, PlaudFile.OFF_HEADER_SIZE)
        ByteArray(12) { 7 }.copyInto(file, PlaudFile.OFF_CHACHA_NONCE)
        Bytes.u32Le(0).copyInto(file, PlaudFile.OFF_SEGMENT_SIZE)
        file[PlaudFile.HEADER_SIZE] = 0xAA.toByte()

        val decryptor = object : FileDecryptor {
            override fun unwrapContentKey(privateKeyPem: String, keyCipher: ByteArray): ByteArray =
                ByteArray(40) { it.toByte() } // longer than 32 → last 32 used

            override fun chacha20Decrypt(
                key: ByteArray,
                nonce: ByteArray,
                initialCounter: Long,
                segmentSize: Long,
                ciphertext: ByteArray,
            ): ByteArray {
                assertEquals(32, key.size)
                assertEquals(8.toByte(), key[0]) // last 32 of 0..39 start at 8
                assertEquals(39.toByte(), key[31])
                return ciphertext // echo for test
            }
        }

        val out = PlaudFileCrypto.decrypt(file, "PEM", decryptor)
        assertEquals(0xAA.toByte(), out[0])
    }
}
