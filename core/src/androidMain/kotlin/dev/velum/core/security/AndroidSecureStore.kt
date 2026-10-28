package dev.velum.core.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.IllegalBlockSizeException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Secure store backed by the Android Keystore (AES-256-GCM).
 *
 * The AES-256 key is generated inside AndroidKeyStore and is **non-exportable**
 * (Keystore hardware/software boundary — `SecretKey.encoded` is unavailable).
 * Only ciphertext is persisted in private SharedPreferences. Used for API keys
 * and imported device identity. Never log decrypted values.
 *
 * Wire format matches the JVM AES-GCM secure payload:
 * `Base64( version:1 || ivLength:1 || iv || ciphertext+tag )`. Legacy
 * unversioned blobs (`ivLength:1 || iv || ciphertext+tag`) still decrypt.
 *
 * Keystore failure policy ([KeystoreFailureClassifier] /
 * [KeystoreAliasResolvePolicy]):
 * - [KeystoreVaultAction.ClearAndRegenerate] — only
 *   `KeyPermanentlyInvalidatedException` (the old key is gone). Delete the alias,
 *   clear every ciphertext entry, mint a fresh key.
 * - [KeystoreVaultAction.KeepEntries] — every other exception. Stored ciphertext
 *   is left untouched; a transient Keystore error must never wipe the vault.
 * - Alias resolution: create a key only when the alias is absent (or after
 *   permanent invalidation). A transient `getEntry` failure (including the
 *   API 35 emulator NPE) keeps the alias and every entry.
 *
 * Decrypt policy matches the JVM store: drop an entry only when its blob is
 * structurally malformed; crypto / auth / Keystore failures keep the ciphertext
 * and return null.
 */
class AndroidSecureStore(
    context: Context,
    private val prefsName: String = PREFS_NAME,
    private val keyAlias: String = KEY_ALIAS,
) : SecureStore {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences by lazy {
        appContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    }
    private val random = SecureRandom()

    override suspend fun putString(key: String, value: String) {
        withContext(Dispatchers.IO) {
            val encrypted = encrypt(value.toByteArray(Charsets.UTF_8))
            prefs.edit().putString(key, encrypted).commit()
        }
    }

    override suspend fun getString(key: String): String? = withContext(Dispatchers.IO) {
        val stored = prefs.getString(key, null) ?: return@withContext null
        val secretKey = try {
            getOrCreateKey()
        } catch (t: Throwable) {
            Log.w(TAG, "Keystore unavailable during read: ${t::class.java.name}")
            if (KeystoreFailureClassifier.actionFor(t) == KeystoreVaultAction.ClearAndRegenerate) {
                clearVaultAndKey()
            }
            return@withContext null
        }
        when (val result = decryptResult(secretKey, stored)) {
            is VaultDecrypt.Ok ->
                result.plaintext.toString(Charsets.UTF_8)
            VaultDecrypt.StructurallyMalformed -> {
                // Only drop blobs that are not valid vault payloads.
                prefs.edit().remove(key).commit()
                null
            }
            VaultDecrypt.CryptographicFailure -> {
                // Wrong key / auth failure — keep ciphertext.
                null
            }
            is VaultDecrypt.KeystoreFailure -> {
                Log.w(TAG, "Keystore failure during decrypt: ${result.throwableClassName}")
                if (result.clearVault) {
                    clearVaultAndKey()
                }
                // Transient Keystore errors keep the stored ciphertext.
                null
            }
        }
    }

    override suspend fun remove(key: String) {
        withContext(Dispatchers.IO) {
            prefs.edit().remove(key).commit()
        }
    }

    override suspend fun contains(key: String): Boolean = withContext(Dispatchers.IO) {
        prefs.contains(key)
    }

    override suspend fun wipe() {
        withContext(Dispatchers.IO) {
            clearVaultAndKey()
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val aliasPresent = keyStore.containsAlias(keyAlias)
        if (!aliasPresent) {
            return createKey()
        }
        // Alias exists — never mint a replacement unless permanently invalidated.
        // API 35 emulator NPE ("invalid null input") is transient: keep the alias.
        val entryResult = runCatching {
            keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry
        }
        val entry = entryResult.getOrNull()
        val failure = entryResult.exceptionOrNull()
        return when (
            KeystoreAliasResolvePolicy.decide(
                aliasPresent = true,
                entryObtained = entry != null,
                getEntryFailure = failure,
            )
        ) {
            KeystoreAliasResolveAction.UseExisting ->
                entry!!.secretKey
            KeystoreAliasResolveAction.CreateMissing ->
                createKey()
            KeystoreAliasResolveAction.ClearAndRegenerate -> {
                val name = failure?.let { it::class.java.name } ?: "unknown"
                Log.w(TAG, "Key permanently invalidated on getEntry; clearing vault: $name")
                clearVaultAndKey()
                createKey()
            }
            KeystoreAliasResolveAction.PropagateTransient -> {
                val t = failure
                    ?: IllegalStateException("Keystore alias present but SecretKeyEntry missing")
                Log.w(TAG, "Keystore getEntry failed; retaining alias: ${t::class.java.name}")
                throw t
            }
        }
    }

    private fun createKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        // Keys minted in AndroidKeyStore are non-exportable by design (no raw key bytes).
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    /**
     * Permanent invalidation / explicit wipe: the Keystore key is gone, so
     * ciphertext sealed with it is unreadable — clear the vault and the alias.
     */
    private fun clearVaultAndKey() {
        deleteKeyAliasOnly()
        prefs.edit().clear().commit()
    }

    private fun deleteKeyAliasOnly() {
        runCatching {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (keyStore.containsAlias(keyAlias)) {
                keyStore.deleteEntry(keyAlias)
            }
        }.onFailure { t ->
            Log.w(TAG, "Failed to delete Keystore alias: ${t::class.java.name}")
        }
    }

    private fun encrypt(plaintext: ByteArray): String {
        return try {
            encryptWith(getOrCreateKey(), plaintext)
        } catch (t: Throwable) {
            when (KeystoreFailureClassifier.actionFor(t)) {
                KeystoreVaultAction.ClearAndRegenerate -> {
                    Log.w(TAG, "Key permanently invalidated; clearing vault: ${t::class.java.name}")
                    clearVaultAndKey()
                    encryptWith(createKey(), plaintext)
                }
                KeystoreVaultAction.KeepEntries -> {
                    // Transient Keystore error — never wipe stored ciphertext.
                    Log.w(TAG, "Keystore encrypt failed; retaining vault: ${t::class.java.name}")
                    throw t
                }
            }
        }
    }

    private fun encryptWith(key: SecretKey, plaintext: ByteArray): String {
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val ciphertext = cipher.doFinal(plaintext)
        val payload = ByteBuffer.allocate(2 + iv.size + ciphertext.size)
            .put(FORMAT_VERSION)
            .put(iv.size.toByte())
            .put(iv)
            .put(ciphertext)
            .array()
        return Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    private fun decryptResult(key: SecretKey, encoded: String): VaultDecrypt {
        val payload = try {
            Base64.decode(encoded, Base64.NO_WRAP)
        } catch (_: IllegalArgumentException) {
            return VaultDecrypt.StructurallyMalformed
        }
        if (payload.isEmpty()) return VaultDecrypt.StructurallyMalformed

        val buffer = ByteBuffer.wrap(payload)
        if (!buffer.hasRemaining()) return VaultDecrypt.StructurallyMalformed
        val first = buffer.get().toInt() and 0xFF
        val ivLength = when {
            first == (FORMAT_VERSION.toInt() and 0xFF) -> {
                if (!buffer.hasRemaining()) return VaultDecrypt.StructurallyMalformed
                buffer.get().toInt() and 0xFF
            }
            // Legacy unversioned blobs: first byte is the IV length.
            first in 12..32 -> first
            else -> return VaultDecrypt.StructurallyMalformed
        }
        if (ivLength !in 12..32) return VaultDecrypt.StructurallyMalformed
        if (buffer.remaining() < ivLength + GCM_TAG_BYTES) {
            return VaultDecrypt.StructurallyMalformed
        }
        val iv = ByteArray(ivLength)
        val ciphertext = try {
            buffer.get(iv)
            ByteArray(buffer.remaining()).also { buffer.get(it) }
        } catch (_: BufferUnderflowException) {
            return VaultDecrypt.StructurallyMalformed
        }

        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            VaultDecrypt.Ok(cipher.doFinal(ciphertext))
        } catch (_: AEADBadTagException) {
            VaultDecrypt.CryptographicFailure
        } catch (_: BadPaddingException) {
            VaultDecrypt.CryptographicFailure
        } catch (_: IllegalBlockSizeException) {
            VaultDecrypt.CryptographicFailure
        } catch (_: IllegalArgumentException) {
            VaultDecrypt.StructurallyMalformed
        } catch (t: Throwable) {
            val clear = KeystoreFailureClassifier.actionFor(t) ==
                KeystoreVaultAction.ClearAndRegenerate
            VaultDecrypt.KeystoreFailure(
                throwableClassName = t::class.java.name,
                clearVault = clear,
            )
        }
    }

    private sealed class VaultDecrypt {
        class Ok(val plaintext: ByteArray) : VaultDecrypt()
        data object StructurallyMalformed : VaultDecrypt()
        data object CryptographicFailure : VaultDecrypt()
        class KeystoreFailure(
            val throwableClassName: String,
            val clearVault: Boolean,
        ) : VaultDecrypt()
    }

    companion object {
        private const val TAG = "VelumSecureStore"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "dev.velum.secure_store_aes"
        private const val PREFS_NAME = "velum_secure_encrypted"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val GCM_TAG_BYTES = 16
        private const val IV_BYTES = 12
        private const val FORMAT_VERSION: Byte = 1
    }
}
