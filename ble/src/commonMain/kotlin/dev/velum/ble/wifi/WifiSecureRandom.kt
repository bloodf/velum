package dev.velum.ble.wifi

/** 96-bit SoftAP AEAD nonce from a platform CSPRNG. Never log or print the bytes. */
internal expect fun wifiSecureRandomNonce(): ByteArray
