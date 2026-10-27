package dev.velum.ai.local

actual fun createProfileCipher(keyMaterial: ByteArray): ProfileCipher = PassthroughProfileCipher
