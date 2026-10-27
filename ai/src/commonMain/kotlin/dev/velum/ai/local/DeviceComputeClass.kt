package dev.velum.ai.local

/**
 * Coarse on-device capacity for picking the Tiny / Standard / Quality ASR tier.
 * Derived from RAM (and optionally SoC hints on Android).
 */
enum class DeviceComputeClass {
    /** ≤ ~3 GiB usable / low memoryClass — Whistle or Whisper Tiny by language. */
    Low,
    /** Typical mid-range phones. */
    Mid,
    /** ≥ ~6 GiB — full Parakeet / Whisper turbo allowed. */
    High,
}

/**
 * How the user wants local ASR sized. [Auto] follows [DeviceComputeClass].
 */
enum class AsrSizeTier {
    Auto,
    Tiny,
    Standard,
    Quality,
}

expect fun detectDeviceComputeClass(): DeviceComputeClass
