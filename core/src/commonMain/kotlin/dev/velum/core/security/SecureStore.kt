package dev.velum.core.security

import dev.velum.protocol.DeviceIdentityKeys
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Platform secure storage (Android Keystore / iOS Keychain / OS keyring on desktop).
 * Implementations must never write secrets to logs or plaintext prefs.
 */
interface SecureStore {
    suspend fun putString(key: String, value: String)
    suspend fun getString(key: String): String?
    suspend fun remove(key: String)
    suspend fun contains(key: String): Boolean
}

object SecureKeys {
    const val AI_API_KEY = "ai.api_key"
    const val DEVICE_PRIVATE_KEY_PEM = "device.private_key_pem"
    const val DEVICE_PUBLIC_KEY_PEM = "device.public_key_pem"
    const val DEVICE_SERIAL_SIGNATURE = "device.serial_signature_b64"
    const val DEVICE_BIND_TOKEN = "device.bind_token"
    const val DEVICE_SERIAL = "device.serial"
}

/**
 * One-time import of Plaud-minted identity material.
 * Values come from the user's own account / device; Velum never ships keys.
 */
interface DeviceKeyImporter {
    suspend fun importKeys(
        serialNumber: String,
        serialSignatureBase64: String,
        publicKeyPem: String,
        privateKeyPem: String,
        bindToken: String,
    )

    suspend fun clearKeys()

    suspend fun hasKeys(): Boolean

    /** Load imported identity material, or null when incomplete. */
    suspend fun loadKeys(): DeviceIdentityKeys?
}

@OptIn(ExperimentalEncodingApi::class)
class SecureDeviceKeyImporter(
    private val store: SecureStore,
) : DeviceKeyImporter {
    override suspend fun importKeys(
        serialNumber: String,
        serialSignatureBase64: String,
        publicKeyPem: String,
        privateKeyPem: String,
        bindToken: String,
    ) {
        store.putString(SecureKeys.DEVICE_SERIAL, serialNumber)
        store.putString(SecureKeys.DEVICE_SERIAL_SIGNATURE, serialSignatureBase64)
        store.putString(SecureKeys.DEVICE_PUBLIC_KEY_PEM, publicKeyPem)
        store.putString(SecureKeys.DEVICE_PRIVATE_KEY_PEM, privateKeyPem)
        store.putString(SecureKeys.DEVICE_BIND_TOKEN, bindToken)
    }

    override suspend fun clearKeys() {
        listOf(
            SecureKeys.DEVICE_SERIAL,
            SecureKeys.DEVICE_SERIAL_SIGNATURE,
            SecureKeys.DEVICE_PUBLIC_KEY_PEM,
            SecureKeys.DEVICE_PRIVATE_KEY_PEM,
            SecureKeys.DEVICE_BIND_TOKEN,
        ).forEach { store.remove(it) }
    }

    override suspend fun hasKeys(): Boolean =
        store.contains(SecureKeys.DEVICE_PRIVATE_KEY_PEM) &&
            store.contains(SecureKeys.DEVICE_SERIAL_SIGNATURE) &&
            store.contains(SecureKeys.DEVICE_BIND_TOKEN)

    override suspend fun loadKeys(): DeviceIdentityKeys? {
        val serial = store.getString(SecureKeys.DEVICE_SERIAL) ?: return null
        val sigB64 = store.getString(SecureKeys.DEVICE_SERIAL_SIGNATURE) ?: return null
        val publicPem = store.getString(SecureKeys.DEVICE_PUBLIC_KEY_PEM) ?: return null
        val privatePem = store.getString(SecureKeys.DEVICE_PRIVATE_KEY_PEM) ?: return null
        val bind = store.getString(SecureKeys.DEVICE_BIND_TOKEN) ?: return null
        val signature = Base64.decode(sigB64)
        if (signature.size != 256) return null
        return DeviceIdentityKeys(
            serialNumber = serial,
            serialSignature = signature,
            publicKeyPem = publicPem,
            privateKeyPem = privatePem,
            bindToken = bind,
        )
    }
}
