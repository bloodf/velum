package dev.velum.app.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_CTX
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.CoreCrypto.CC_SHA256_Final
import platform.CoreCrypto.CC_SHA256_Init
import platform.CoreCrypto.CC_SHA256_Update
import platform.posix.EINTR
import platform.posix.O_RDONLY
import platform.posix.close
import platform.posix.errno
import platform.posix.open
import platform.posix.read

@OptIn(ExperimentalForeignApi::class)
actual fun sha256File(absolutePath: String): String {
    val fd = open(absolutePath, O_RDONLY)
    check(fd >= 0) { "Cannot open $absolutePath (errno=$errno)" }
    try {
        return memScoped {
            val context = alloc<CC_SHA256_CTX>()
            check(CC_SHA256_Init(context.ptr) == 1)
            val buffer = ByteArray(64 * 1024)
            buffer.usePinned { input ->
                while (true) {
                    val count = read(fd, input.addressOf(0), buffer.size.toULong())
                    if (count < 0) {
                        val readError = errno
                        if (readError == EINTR) continue
                        error("Cannot read $absolutePath (errno=$readError)")
                    }
                    if (count == 0L) break
                    check(CC_SHA256_Update(context.ptr, input.addressOf(0), count.toUInt()) == 1)
                }
            }
            val out = UByteArray(CC_SHA256_DIGEST_LENGTH)
            out.usePinned { digest ->
                check(CC_SHA256_Final(digest.addressOf(0), context.ptr) == 1)
            }
            out.joinToString("") { b -> b.toString(16).padStart(2, '0') }
        }
    } finally {
        close(fd)
    }
}

@OptIn(ExperimentalForeignApi::class)
actual fun sha256Bytes(bytes: ByteArray): String {
    val out = UByteArray(CC_SHA256_DIGEST_LENGTH)
    bytes.usePinned { input ->
        out.usePinned { digest ->
            CC_SHA256(if (bytes.isEmpty()) null else input.addressOf(0), bytes.size.toUInt(), digest.addressOf(0))
        }
    }
    return out.joinToString("") { b -> b.toString(16).padStart(2, '0') }
}
