package dev.velum.core.bundle

import java.security.SecureRandom
import java.text.Normalizer

private val secureRandom = SecureRandom()

actual fun secureRandomBytes(count: Int): ByteArray = ByteArray(count).also { secureRandom.nextBytes(it) }

actual fun normalizeNfc(passphrase: String): String = Normalizer.normalize(passphrase, Normalizer.Form.NFC)
