@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.velum.ai.local

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault
import platform.Security.errSecSuccess

actual fun createProfileCipher(keyMaterial: ByteArray): ProfileCipher = PassthroughProfileCipher

actual fun generateProfileKey(): ByteArray = ByteArray(32).also { bytes ->
    bytes.usePinned {
        check(SecRandomCopyBytes(kSecRandomDefault, bytes.size.toULong(), it.addressOf(0)) == errSecSuccess) {
            "Unable to create a voice profile key."
        }
    }
}
