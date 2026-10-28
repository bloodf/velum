package dev.velum.ai.local

/**
 * Test-only replica of the pre-#110 deterministic file-key formula.
 * Production sources must not contain this formula — see [NoConstantVoiceKeyInProductionTest].
 */
internal fun legacyConstantKeyBytesForTests(): ByteArray =
    ByteArray(32) { (it * 17 + 41).toByte() }
