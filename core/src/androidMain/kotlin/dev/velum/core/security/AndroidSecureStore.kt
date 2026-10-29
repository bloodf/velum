package dev.velum.core.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.IllegalBlockSizeException
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
 *
 * [keyBackend] is the Keystore seam: production uses [AndroidKeystoreBackend];
 * androidUnitTest may inject a fake. The public constructor always uses the
 * real backend.
 */
class AndroidSecureStore private constructor(
    context: Context,
    private val prefsName: String,
    private val keyAlias: String,
    private val keyBackend: AndroidSecureKeyBackend,
) : SecureStore {
    /**
     * Production constructor — real Android Keystore backend, no test hooks.
     */
    constructor(
        context: Context,
        prefsName: String = PREFS_NAME,
        keyAlias: String = KEY_ALIAS,
    ) : this(context, prefsName, keyAlias, AndroidKeystoreBackend(keyAlias))

    /**
     * Test-only: inject a fake [AndroidSecureKeyBackend] (androidUnitTest).
     */
    internal constructor(
        context: Context,
        keyBackend: AndroidSecureKeyBackend,
        prefsName: String = PREFS_NAME,
        keyAlias: String = KEY_ALIAS,
    ) : this(context, prefsName, keyAlias, keyBackend)

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences by lazy {
        appContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    }

    override suspend fun putString(key: String, value: String) {
        withContext(Dispatchers.IO) {
            val encrypted = encrypt(value.toByteArray(Charsets.UTF_8))
            prefs.edit().putString(key, encrypted).commit()
        }
    }

    override suspend fun getString(key: String): String? = withContext(Dispatchers.IO) {
        val stored = prefs.getString(key, null) ?: return@withContext null
        val secretKey = try {
            keyBackend.getOrCreateKey(onClearVault = { clearVaultAndKey() })
        } catch (t: Throwable) {
            Log.w(TAG, "Keystore unavailable during getString: ${t::class.java.name}")
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

    /**
     * Non-destructive read: never removes prefs entries and never clears or
     * regenerates the Keystore alias. An existing entry that cannot be opened
     * is [SecureRead.Unreadable] (including [KeystoreAliasResolveAction.PropagateTransient]);
     * [SecureRead.Missing] only when the prefs key is absent.
     */
    override suspend fun read(key: String): SecureRead = withContext(Dispatchers.IO) {
        if (!prefs.contains(key)) return@withContext SecureRead.Missing
        val stored = prefs.getString(key, null)
            ?: return@withContext SecureRead.Missing
        val secretKey = try {
            keyBackend.peekExistingKeyForRead()
        } catch (t: Throwable) {
            Log.w(TAG, "Keystore unavailable during read: ${t::class.java.name}")
            // Transient or permanent Keystore failure — Unreadable, never Missing;
            // leave the prefs entry and the alias untouched.
            return@withContext SecureRead.Unreadable(t::class.java.name)
        }
        when (val result = decryptResult(secretKey, stored)) {
            is VaultDecrypt.Ok ->
                SecureRead.Value(result.plaintext.toString(Charsets.UTF_8))
            VaultDecrypt.StructurallyMalformed ->
                SecureRead.Unreadable(VaultDecrypt.StructurallyMalformed::class.java.name)
            VaultDecrypt.CryptographicFailure ->
                SecureRead.Unreadable(VaultDecrypt.CryptographicFailure::class.java.name)
            is VaultDecrypt.KeystoreFailure -> {
                Log.w(TAG, "Keystore failure during read: ${result.throwableClassName}")
                // Never clearVaultAndKey from read — leave entry and alias in place.
                SecureRead.Unreadable(result.throwableClassName)
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

    /**
     * Permanent invalidation / explicit wipe: the Keystore key is gone, so
     * ciphertext sealed with it is unreadable — clear the vault and the alias.
     */
    private fun clearVaultAndKey() {
        keyBackend.deleteAlias()
        prefs.edit().clear().commit()
    }

    private fun encrypt(plaintext: ByteArray): String {
        return try {
            encryptWith(keyBackend.getOrCreateKey(onClearVault = { clearVaultAndKey() }), plaintext)
        } catch (t: Throwable) {
            when (KeystoreFailureClassifier.actionFor(t)) {
                KeystoreVaultAction.ClearAndRegenerate -> {
                    Log.w(TAG, "Key permanently invalidated; clearing vault: ${t::class.java.name}")
                    clearVaultAndKey()
                    // Prefer getOrCreateKey over createKey: if deleteAlias failed silently,
                    // a broken leftover alias must throw instead of being overwritten.
                    encryptWith(
                        keyBackend.getOrCreateKey(onClearVault = { clearVaultAndKey() }),
                        plaintext,
                    )
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
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // AndroidKeyStore AES keys require randomized encryption: never pass a
        // caller IV. The provider chooses one; read it back from the cipher.
        val iv = keyBackend.initEncrypt(cipher, key)
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
        private const val KEY_ALIAS = "dev.velum.secure_store_aes"
        private const val PREFS_NAME = "velum_secure_encrypted"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val GCM_TAG_BYTES = 16
        private const val FORMAT_VERSION: Byte = 1
    }
}
