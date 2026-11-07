package dev.velum.ble.wifi

import dev.velum.protocol.Protocol20SessionSecret
import dev.velum.protocol.SessionCipher
import dev.whyoleg.cryptography.providers.cryptokit.CryptoKit

actual fun createWifiFrameCipher(
    cipher: SessionCipher,
    secret: Protocol20SessionSecret,
    mode: WifiPayloadCipher,
): WifiFrameCipher = when (mode) {
    WifiPayloadCipher.ChaCha20Poly1305 -> BleSessionWifiFrameCipher(cipher, secret)
    WifiPayloadCipher.AesGcm ->
        AppleAesGcmWifiFrameCipher(secret)
}

@OptIn(dev.whyoleg.cryptography.DelicateCryptographyApi::class)
private class AppleAesGcmWifiFrameCipher(private val secret: Protocol20SessionSecret) : WifiFrameCipher {
    override val mode = WifiPayloadCipher.AesGcm
    private val cipher = dev.whyoleg.cryptography.CryptographyProvider.CryptoKit
        .get(dev.whyoleg.cryptography.algorithms.AES.GCM).keyDecoder()
        .decodeFromByteArrayBlocking(dev.whyoleg.cryptography.algorithms.AES.Key.Format.RAW, secret.key).cipher()

    init {
        require(secret.key.size == 32)
        require(secret.nonce.size == 12)
    }

    override fun seal(sequence: Long, packet: ByteArray): ByteArray =
        cipher.encryptWithIvBlocking(secret.nonce, dev.velum.protocol.Bytes.u32Le(sequence) + packet, secret.aad)

    override fun open(sequence: Long, ciphertextAndTag: ByteArray): Pair<Long, ByteArray>? {
        if (ciphertextAndTag.size < 16) return null
        val plain = try {
            cipher.decryptWithIvBlocking(secret.nonce, ciphertextAndTag, secret.aad)
        } catch (failure: IllegalStateException) {
            // 0.6.0 swiftTry erases NSError's type. Restrict this to the provider's native decrypt failure.
            if (failure.message?.startsWith("Dwc call failed:") != true) throw failure
            return null
        }
        if (plain.size < 4 || dev.velum.protocol.Bytes.readU32Le(plain, 0) != sequence) return null
        return sequence to plain.copyOfRange(4, plain.size)
    }
}
