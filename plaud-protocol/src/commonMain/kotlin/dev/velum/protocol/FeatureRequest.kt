package dev.velum.protocol

/**
 * Cmd 138 FeatureRequest response (APK 3.30.0).
 *
 * After skipping the first 3 payload bytes, the next byte is a feature bitmap.
 * Bit `0x08` means the device supports AES-GCM on the Wi-Fi transfer pipe
 * (otherwise ChaCha20-Poly1305 with the BLE session material).
 */
object FeatureRequest {
    const val CMD: Int = 138
    const val WIFI_AES_GCM_BIT: Int = 0x08
    const val BITMAP_OFFSET: Int = 3

    fun encodeRequest(params: ByteArray = ByteArray(0)): ByteArray =
        Framing.encodeCommand(CMD, params)

    fun parse(payload: ByteArray): FeatureFlags {
        if (payload.size <= BITMAP_OFFSET) {
            return FeatureFlags(wifiAesGcm = false, rawBitmap = 0)
        }
        val bitmap = payload[BITMAP_OFFSET].toInt() and 0xFF
        return FeatureFlags(
            wifiAesGcm = bitmap and WIFI_AES_GCM_BIT != 0,
            rawBitmap = bitmap,
        )
    }
}

data class FeatureFlags(
    val wifiAesGcm: Boolean,
    val rawBitmap: Int,
)
