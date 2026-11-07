package dev.velum.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class Protocol20SessionSecretTest {
    @Test fun toString_never_shows_key_material() {
        val secret = Protocol20SessionSecret(ByteArray(32) { 0x41 }, ByteArray(12) { 0x42 }, ByteArray(12) { 0x43 })
        val text = secret.toString()
        assertEquals("Protocol20SessionSecret([redacted])", text)
        assertFalse(text.contains("65") || text.contains("AAAA") || text.contains("0x41"), text)
        // Still compares by content.
        assertEquals(secret, Protocol20SessionSecret(ByteArray(32) { 0x41 }, ByteArray(12) { 0x42 }, ByteArray(12) { 0x43 }))
    }
}
