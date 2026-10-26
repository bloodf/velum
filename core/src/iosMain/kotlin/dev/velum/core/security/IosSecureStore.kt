package dev.velum.core.security

/**
 * Foundation stub. M3 should use the iOS Keychain via Kotlin/Native.
 * Falls back to in-memory so the shell app can run in the simulator.
 */
class IosSecureStore : SecureStore by InMemorySecureStore()
