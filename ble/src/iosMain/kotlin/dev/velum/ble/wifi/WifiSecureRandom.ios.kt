package dev.velum.ble.wifi

internal actual fun wifiSecureRandomNonce(): ByteArray =
    error("Wi-Fi SoftAP AEAD nonce CSPRNG on iOS lands with CryptoKit later")
