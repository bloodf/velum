@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.velum.core.bundle

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSString
import platform.Foundation.precomposedStringWithCanonicalMapping
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecSuccess
import platform.Security.kSecRandomDefault

actual fun secureRandomBytes(count: Int): ByteArray = ByteArray(count).also { bytes ->
    if (count > 0) {
        bytes.usePinned {
            check(SecRandomCopyBytes(kSecRandomDefault, count.convert(), it.addressOf(0)) == errSecSuccess) {
                "Unable to read secure random bytes."
            }
        }
    }
}

@Suppress("CAST_NEVER_SUCCEEDS")
actual fun normalizeNfc(passphrase: String): String = (passphrase as NSString).precomposedStringWithCanonicalMapping
