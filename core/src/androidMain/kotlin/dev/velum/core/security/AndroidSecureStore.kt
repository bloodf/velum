package dev.velum.core.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.nio.ByteBuffer
import java.security.InvalidKeyException
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Secure store backed by the Android Keystore (AES-256-GCM).
 *
 * The secret key never leaves the Keystore; only ciphertext is persisted in
 * private SharedPreferences. Used for API keys and imported device identity.
 *
 * If the Keystore entry is permanently invalidated (emulator wipe / OEM quirks),
 * the alias is deleted and a fresh key is created so cold start cannot crash.
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

    override suspend fun putString(key: String, value: String) {
        withContext(Dispatchers.IO) {
            val encrypted = encrypt(value.toByteArray(Charsets.UTF_8))
            prefs.edit().putString(key, encrypted).commit()
        }
    }

    override suspend fun getString(key: String): String? = withContext(Dispatchers.IO) {
        val stored = prefs.getString(key, null) ?: return@withContext null
        runCatching {
            decrypt(stored).toString(Charsets.UTF_8)
        }.getOrElse {
            // Corrupt, invalidated key, or legacy plaintext — remove and treat as missing.
            prefs.edit().remove(key).commit()
            null
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

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey
        return createKey()
    }

    private fun createKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun deleteKey() {
        runCatching {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (keyStore.containsAlias(keyAlias)) {
                keyStore.deleteEntry(keyAlias)
            }
        }.onFailure { t ->
            Log.w(TAG, "Failed to delete invalidated Keystore alias", t)
        }
        // Ciphertext sealed with the old key is unreadable — clear the vault.
        prefs.edit().clear().commit()
    }

    private fun encrypt(plaintext: ByteArray): String {
        return try {
            encryptWith(getOrCreateKey(), plaintext)
        } catch (t: Throwable) {
            if (!isInvalidatedKey(t)) throw t
            Log.w(TAG, "Keystore key invalidated; recreating", t)
            deleteKey()
            encryptWith(createKey(), plaintext)
        }
    }

    private fun encryptWith(key: SecretKey, plaintext: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)
        val payload = ByteBuffer.allocate(1 + iv.size + ciphertext.size)
            .put(iv.size.toByte())
            .put(iv)
            .put(ciphertext)
            .array()
        return Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): ByteArray {
        val payload = Base64.decode(encoded, Base64.NO_WRAP)
        require(payload.isNotEmpty()) { "Empty ciphertext" }
        val buffer = ByteBuffer.wrap(payload)
        val ivLength = buffer.get().toInt() and 0xFF
        require(ivLength in 12..32) { "Unexpected IV length: $ivLength" }
        val iv = ByteArray(ivLength)
        buffer.get(iv)
        val ciphertext = ByteArray(buffer.remaining())
        buffer.get(ciphertext)
        return try {
            decryptWith(getOrCreateKey(), iv, ciphertext)
        } catch (t: Throwable) {
            if (!isInvalidatedKey(t)) throw t
            Log.w(TAG, "Keystore key invalidated during decrypt; recreating", t)
            deleteKey()
            // Caller treats failure as missing value after prefs clear.
            throw t
        }
    }

    private fun decryptWith(key: SecretKey, iv: ByteArray, ciphertext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    private fun isInvalidatedKey(t: Throwable): Boolean {
        var cur: Throwable? = t
        while (cur != null) {
            when (cur) {
                is KeyPermanentlyInvalidatedException,
                is UnrecoverableKeyException,
                is InvalidKeyException,
                -> return true
            }
            // Some OEMs wrap the typed exception.
            if (cur.message?.contains("Key permanently invalidated", ignoreCase = true) == true) {
                return true
            }
            cur = cur.cause
        }
        return false
    }

    companion object {
        private const val TAG = "VelumSecureStore"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "dev.velum.secure_store_aes"
        private const val PREFS_NAME = "velum_secure_encrypted"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
    }
}
