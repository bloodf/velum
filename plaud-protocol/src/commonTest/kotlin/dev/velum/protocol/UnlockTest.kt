package dev.velum.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class UnlockTest {
    @Test
    fun encodeSnSignature_threeChunksOf100_plusRemainder() {
        val signature = ByteArray(256) { it.toByte() }
        val chunks = Unlock.encodeSnSignatureChunks(signature)
        assertEquals(3, chunks.size)
        chunks.forEachIndexed { index, frame ->
            val parsed = Unlock.parseChunk(frame)
            assertEquals(Commands.UNLOCK_SN_SIGNATURE, parsed.opcode)
            assertEquals(3, parsed.total)
            assertEquals(index, parsed.index)
        }
        assertEquals(100, Unlock.parseChunk(chunks[0]).data.size)
        assertEquals(100, Unlock.parseChunk(chunks[1]).data.size)
        assertEquals(56, Unlock.parseChunk(chunks[2]).data.size)
        val rebuilt = Unlock.reassemble(chunks.map(Unlock::parseChunk))
        assertTrue(rebuilt.contentEquals(signature))
    }

    @Test
    fun encodeSnSignature_rejectsWrongLength() {
        assertFailsWith<IllegalArgumentException> {
            Unlock.encodeSnSignatureChunks(ByteArray(255))
        }
    }

    @Test
    fun parseHeader_layout() {
        val key = ByteArray(32) { 1 }
        val nonce = ByteArray(12) { 2 }
        val aad = ByteArray(12) { 3 }
        val test = ByteArray(24) { 4 }
        val blob = key + nonce + aad + test
        val secret = UnlockSecretLayout.parseHeader(blob)
        assertTrue(secret.key.contentEquals(key))
        assertTrue(secret.nonce.contentEquals(nonce))
        assertTrue(secret.aad.contentEquals(aad))
        assertTrue(UnlockSecretLayout.testCiphertext(blob).contentEquals(test))
        assertTrue(UnlockSecretLayout.TEST_PLAINTEXT.contentEquals("PLAUD.AI".encodeToByteArray()))
    }

    @Test
    fun deviceIdentityKeys_requires256ByteSignature() {
        assertFailsWith<IllegalArgumentException> {
            DeviceIdentityKeys(
                serialNumber = "881000000000000",
                serialSignature = ByteArray(16),
                publicKeyPem = "-----BEGIN PUBLIC KEY-----\n...\n-----END PUBLIC KEY-----",
                privateKeyPem = "-----BEGIN PRIVATE KEY-----\n...\n-----END PRIVATE KEY-----",
                bindToken = "user",
            )
        }
    }
}
