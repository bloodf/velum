package dev.velum.ble.wifi

import dev.velum.protocol.Protocol20SessionSecret
import dev.velum.protocol.SessionCipher

actual fun createWifiFrameCipher(
    cipher: SessionCipher,
    secret: Protocol20SessionSecret,
    mode: WifiPayloadCipher,
): WifiFrameCipher = when (mode) {
    WifiPayloadCipher.ChaCha20Poly1305 -> BleSessionWifiFrameCipher(cipher, secret)
    WifiPayloadCipher.AesGcm ->
        error("AES-GCM Wi-Fi frame cipher on iOS lands with CryptoKit later")
}
