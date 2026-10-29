package dev.velum.protocol.identity

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Base64 encode/decode for identity signatures. Failures never include input bytes
 * in exception messages.
 */
@OptIn(ExperimentalEncodingApi::class)
internal object IdentityBase64 {
    fun encode(data: ByteArray): String = Base64.encode(data)

    /**
     * @return decoded bytes, or null when [text] is not valid base64.
     */
    fun decodeOrNull(text: String): ByteArray? =
        try {
            Base64.decode(text)
        } catch (_: IllegalArgumentException) {
            null
        }
}
