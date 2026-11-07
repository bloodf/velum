package dev.velum.core.integrations

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class WebhookCryptoIosTest {
    @Test
    fun emptyKeyAndBody() {
        assertHmac("b613679a0814d9ec772f95d778c35fc5ff1697c493715653c6c712144292c5ad", byteArrayOf(), byteArrayOf())
    }

    @Test
    fun emptyBody() {
        assertHmac(
            "5d5d139563c95b5967b9bd9a8c9b233a9dedb45072794cd232dc1b74832607d0",
            "key".encodeToByteArray(),
            byteArrayOf(),
        )
    }

    @Test
    fun emptyKey() {
        assertHmac(
            "fd7adb152c05ef80dccf50a1fa4c05d5a3ec6da95575fc312ae7c5d091836351",
            byteArrayOf(),
            "abc".encodeToByteArray(),
        )
    }

    // RFC 4231 section 4.2, binary key and ASCII message.
    @Test
    fun rfc4231Case1() {
        assertHmac(
            "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7",
            ByteArray(20) { 0x0b },
            "Hi There".encodeToByteArray(),
        )
    }

    // RFC 4231 section 4.6, key larger than the SHA-256 block size.
    @Test
    fun rfc4231Case6() {
        assertHmac(
            "60e431591ee0b67f0d8a26aacbf5b77f8e0bc6213728c5140546040f0ee37f54",
            ByteArray(131) { 0xaa.toByte() },
            "Test Using Larger Than Block-Size Key - Hash Key First".encodeToByteArray(),
        )
    }

    @Test
    fun signerHandlesEmptyBody() {
        assertEquals(
            "sha256=5d5d139563c95b5967b9bd9a8c9b233a9dedb45072794cd232dc1b74832607d0",
            WebhookSigner.signatureHeaderValue("key", ""),
        )
    }

    private fun assertHmac(expected: String, secret: ByteArray, body: ByteArray) {
        val originalSecret = secret.copyOf()
        val originalBody = body.copyOf()
        assertEquals(expected, WebhookCrypto.hmacSha256Hex(secret, body))
        assertContentEquals(originalSecret, secret)
        assertContentEquals(originalBody, body)
    }
}
