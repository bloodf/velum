package dev.velum.ai.local

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH

@OptIn(ExperimentalForeignApi::class)
actual fun sha256Hex(bytes: ByteArray): String {
    val digest = UByteArray(CC_SHA256_DIGEST_LENGTH)
    bytes.usePinned { input ->
        digest.usePinned { out ->
            CC_SHA256(input.addressOf(0), bytes.size.toUInt(), out.addressOf(0))
        }
    }
    return digest.joinToString("") { b ->
        b.toInt().toString(16).padStart(2, '0')
    }
}
