package dev.velum.core.security

import dev.velum.protocol.DeviceIdentityKeys
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

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

/** Storage failure is distinct from an absent credential. No secret/provider details in the message. */
class SecureStoreUnavailableException(cause: Throwable) : IllegalStateException(
    "Secure storage is unavailable. Stored data was preserved. Try again.", cause,
)

object SecureKeys {
    // Losing this key makes on-disk voice embeddings permanently unreadable.
    const val VOICE_PROFILE_AES_KEY = "voice.profile_aes_key_b64"
    const val AI_API_KEY = "ai.api_key"

    /** Per-endpoint-profile API key. Legacy [AI_API_KEY] mirrors the default profile. */
    fun apiKey(profileId: String): String = "ai.api_key.$profileId"

    const val DEVICE_PRIVATE_KEY_PEM = "device.private_key_pem"
    const val DEVICE_PUBLIC_KEY_PEM = "device.public_key_pem"
    const val DEVICE_SERIAL_SIGNATURE = "device.serial_signature_b64"
    const val DEVICE_BIND_TOKEN = "device.bind_token"
    const val DEVICE_SERIAL = "device.serial"
    /** One encrypted value makes an identity update coherent across failures/readers. */
    const val DEVICE_IDENTITY_BUNDLE = "device.identity_bundle_v1"
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
        val signature = try {
            Base64.decode(serialSignatureBase64.trim())
        } catch (_: IllegalArgumentException) {
            // Do not retain the decoder cause: its message can echo imported identity.
            throw IllegalArgumentException("snSignature is not valid base64")
        }
        require(signature.size == 256) {
            "snSignature must decode to 256 bytes (got ${signature.size})"
        }
        val bundle = StoredDeviceIdentity(
            version = IDENTITY_BUNDLE_VERSION,
            serial = serialNumber.trim(),
            signatureBase64 = serialSignatureBase64.trim(),
            bindToken = bindToken.trim(),
            publicKeyPem = publicKeyPem.trim(),
            privateKeyPem = privateKeyPem.trim(),
        )
        val encoded = identityJson.encodeToString(StoredDeviceIdentity.serializer(), bundle)
        currentCoroutineContext().ensureActive()
        // A provider may report an uncertain commit, but readers still see one
        // complete identity, never fields from separate imports. Legacy bytes stay intact.
        store.putString(SecureKeys.DEVICE_IDENTITY_BUNDLE, encoded)
    }

    override suspend fun clearKeys() {
        listOf(
            SecureKeys.DEVICE_SERIAL,
            SecureKeys.DEVICE_SERIAL_SIGNATURE,
            SecureKeys.DEVICE_PUBLIC_KEY_PEM,
            SecureKeys.DEVICE_PRIVATE_KEY_PEM,
            SecureKeys.DEVICE_BIND_TOKEN,
            // Clear legacy entries before the active bundle so a failed clear
            // cannot expose old credentials as a fallback after removing the bundle.
            SecureKeys.DEVICE_IDENTITY_BUNDLE,
        ).forEach { store.remove(it) }
    }

    override suspend fun hasKeys(): Boolean =
        store.contains(SecureKeys.DEVICE_IDENTITY_BUNDLE) ||
        (store.contains(SecureKeys.DEVICE_SERIAL) &&
            store.contains(SecureKeys.DEVICE_SERIAL_SIGNATURE) &&
            store.contains(SecureKeys.DEVICE_BIND_TOKEN))

    override suspend fun loadKeys(): DeviceIdentityKeys? {
        store.getString(SecureKeys.DEVICE_IDENTITY_BUNDLE)?.let { raw ->
            // An unreadable active bundle must not silently select a legacy identity.
            return decodeBundle(raw)
        }
        val serial = store.getString(SecureKeys.DEVICE_SERIAL) ?: return null
        val sigB64 = store.getString(SecureKeys.DEVICE_SERIAL_SIGNATURE) ?: return null
        val bind = store.getString(SecureKeys.DEVICE_BIND_TOKEN) ?: return null
        val publicPem = store.getString(SecureKeys.DEVICE_PUBLIC_KEY_PEM).orEmpty()
        val privatePem = store.getString(SecureKeys.DEVICE_PRIVATE_KEY_PEM).orEmpty()
        val signature = try {
            Base64.decode(sigB64)
        } catch (_: IllegalArgumentException) {
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

    private fun decodeBundle(raw: String): DeviceIdentityKeys? {
        val bundle = try {
            identityJson.decodeFromString(StoredDeviceIdentity.serializer(), raw)
        } catch (_: SerializationException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (bundle.version != IDENTITY_BUNDLE_VERSION || bundle.serial.isBlank() || bundle.bindToken.isBlank()) return null
        val signature = try {
            Base64.decode(bundle.signatureBase64)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (signature.size != 256) return null
        return DeviceIdentityKeys(bundle.serial, signature, bundle.publicKeyPem, bundle.privateKeyPem, bundle.bindToken)
    }

    private companion object {
        const val IDENTITY_BUNDLE_VERSION = 1

        // Explicit defaults keep the stored form independent of Kotlin default values.
        val identityJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}

/**
 * Internal storage format, independent of the public import/export schema.
 * Serial names are pinned: renaming them makes every stored identity unreadable.
 */
@Serializable
private data class StoredDeviceIdentity(
    @SerialName("version") val version: Int,
    @SerialName("serial") val serial: String,
    @SerialName("signatureBase64") val signatureBase64: String,
    @SerialName("bindToken") val bindToken: String,
    @SerialName("publicKeyPem") val publicKeyPem: String = "",
    @SerialName("privateKeyPem") val privateKeyPem: String = "",
) {
    override fun toString(): String = "StoredDeviceIdentity([redacted])"
}
