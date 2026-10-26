package dev.velum.ai.local

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_CTX
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.CoreCrypto.CC_SHA256_Final
import platform.CoreCrypto.CC_SHA256_Init
import platform.CoreCrypto.CC_SHA256_Update

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

@OptIn(ExperimentalForeignApi::class)
actual fun createSha256Digest(): Sha256Digest = IosSha256Digest()

@OptIn(ExperimentalForeignApi::class)
private class IosSha256Digest : Sha256Digest {
    private val ctx = nativeHeap.alloc<CC_SHA256_CTX>()
    private var finalized = false

    init {
        CC_SHA256_Init(ctx.ptr)
    }

    override fun update(bytes: ByteArray, offset: Int, length: Int) {
        check(!finalized) { "SHA-256 digest already finalized" }
        if (length <= 0) return
        bytes.usePinned { pinned ->
            CC_SHA256_Update(ctx.ptr, pinned.addressOf(offset), length.toUInt())
        }
    }

    override fun digestHex(): String {
        check(!finalized) { "SHA-256 digest already finalized" }
        finalized = true
        val digest = UByteArray(CC_SHA256_DIGEST_LENGTH)
        digest.usePinned { out ->
            CC_SHA256_Final(out.addressOf(0), ctx.ptr)
        }
        nativeHeap.free(ctx.rawPtr)
        return digest.joinToString("") { b ->
            b.toInt().toString(16).padStart(2, '0')
        }
    }
}
