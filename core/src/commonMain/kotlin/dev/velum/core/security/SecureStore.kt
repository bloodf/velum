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

    /** Per-endpoint-profile API key. Legacy [AI_API_KEY] mirrors the default profile. */
    fun apiKey(profileId: String): String = "ai.api_key.$profileId"

    const val DEVICE_PRIVATE_KEY_PEM = "device.private_key_pem"
    const val DEVICE_PUBLIC_KEY_PEM = "device.public_key_pem"
    const val DEVICE_SERIAL_SIGNATURE = "device.serial_signature_b64"
    const val DEVICE_BIND_TOKEN = "device.bind_token"
    const val DEVICE_SERIAL = "device.serial"
    const val WEBHOOK_URL = "integrations.webhook.url"
    const val WEBHOOK_SECRET = "integrations.webhook.secret"
}

/**
 * One-time import of Plaud-minted identity material.
 * Values come from the user's own account / device; Velum never ships keys.
 */
interface DeviceKeyImporter {
    /**
     * One-time import from the official app. RSA PEMs are optional when E2EE is off
     * (FE10 + bind token may suffice — verify on hardware).
     */
    suspend fun importKeys(
        serialNumber: String,
        serialSignatureBase64: String,
        bindToken: String,
        publicKeyPem: String = "",
        privateKeyPem: String = "",
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
        bindToken: String,
        publicKeyPem: String,
        privateKeyPem: String,
    ) {
        require(serialNumber.isNotBlank()) { "serialNumber required" }
        require(serialSignatureBase64.isNotBlank()) { "snSignature (base64) required" }
        require(bindToken.isNotBlank()) { "bind token required" }
        val signature = Base64.decode(serialSignatureBase64.trim())
        require(signature.size == 256) {
            "snSignature must decode to 256 bytes (got ${signature.size})"
        }
        store.putString(SecureKeys.DEVICE_SERIAL, serialNumber.trim())
        store.putString(SecureKeys.DEVICE_SERIAL_SIGNATURE, serialSignatureBase64.trim())
        store.putString(SecureKeys.DEVICE_BIND_TOKEN, bindToken.trim())
        if (publicKeyPem.isNotBlank()) {
            store.putString(SecureKeys.DEVICE_PUBLIC_KEY_PEM, publicKeyPem.trim())
        } else {
            store.remove(SecureKeys.DEVICE_PUBLIC_KEY_PEM)
        }
        if (privateKeyPem.isNotBlank()) {
            store.putString(SecureKeys.DEVICE_PRIVATE_KEY_PEM, privateKeyPem.trim())
        } else {
            store.remove(SecureKeys.DEVICE_PRIVATE_KEY_PEM)
        }
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
        store.contains(SecureKeys.DEVICE_SERIAL) &&
            store.contains(SecureKeys.DEVICE_SERIAL_SIGNATURE) &&
            store.contains(SecureKeys.DEVICE_BIND_TOKEN)

    override suspend fun loadKeys(): DeviceIdentityKeys? {
        val serial = store.getString(SecureKeys.DEVICE_SERIAL) ?: return null
        val sigB64 = store.getString(SecureKeys.DEVICE_SERIAL_SIGNATURE) ?: return null
        val bind = store.getString(SecureKeys.DEVICE_BIND_TOKEN) ?: return null
        val publicPem = store.getString(SecureKeys.DEVICE_PUBLIC_KEY_PEM).orEmpty()
        val privatePem = store.getString(SecureKeys.DEVICE_PRIVATE_KEY_PEM).orEmpty()
        val signature = try {
            Base64.decode(sigB64)
        } catch (_: Throwable) {
            return null
        }
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
