package dev.velum.ble.wifi

import dev.velum.protocol.Protocol20SessionSecret
import dev.velum.protocol.SessionCipher

/**
 * Platform factory: ChaCha via [SessionCipher]; AES-GCM via JCA on JVM/Android.
 * SoftAP frames use a unique 96-bit nonce per message (never reuse key+nonce).
 */
expect fun createWifiFrameCipher(
    cipher: SessionCipher,
    secret: Protocol20SessionSecret,
    mode: WifiPayloadCipher,
): WifiFrameCipher
