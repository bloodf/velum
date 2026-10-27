package dev.velum.app.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.Foundation.NSData
import platform.Foundation.dataWithContentsOfFile
import platform.posix.memcpy

@OptIn(ExperimentalForeignApi::class)
actual fun sha256File(absolutePath: String): String {
    val data = NSData.dataWithContentsOfFile(absolutePath)
        ?: error("Cannot read $absolutePath")
    val bytes = ByteArray(data.length.toInt())
    bytes.usePinned { pinned ->
        memcpy(pinned.addressOf(0), data.bytes, data.length)
    }
    return sha256Bytes(bytes)
}

@OptIn(ExperimentalForeignApi::class)
actual fun sha256Bytes(bytes: ByteArray): String {
    val out = UByteArray(CC_SHA256_DIGEST_LENGTH)
    bytes.usePinned { input ->
        out.usePinned { digest ->
            CC_SHA256(input.addressOf(0), bytes.size.toUInt(), digest.addressOf(0))
        }
    }
    return out.joinToString("") { b -> b.toString(16).padStart(2, '0') }
}
