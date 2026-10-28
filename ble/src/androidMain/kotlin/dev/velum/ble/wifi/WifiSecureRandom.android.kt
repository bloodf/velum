package dev.velum.ble.wifi

import java.security.SecureRandom

private val wifiSecureRandom = SecureRandom()

internal actual fun wifiSecureRandomNonce(): ByteArray =
    ByteArray(WifiAeadFrame.NONCE_LEN).also { wifiSecureRandom.nextBytes(it) }
