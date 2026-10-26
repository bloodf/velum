package dev.velum.ble.wifi

import dev.velum.protocol.Protocol20SessionSecret
import dev.velum.protocol.SessionCipher

/**
 * Platform factory: ChaCha via [SessionCipher]; AES-GCM via JCA on JVM/Android.
 * AES-GCM uses the session's fixed nonce (Plaud protocol parity debt — intentional
 * until a Velum-only path).
 */
expect fun createWifiFrameCipher(
    cipher: SessionCipher,
    secret: Protocol20SessionSecret,
    mode: WifiPayloadCipher,
): WifiFrameCipher
