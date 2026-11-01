package dev.velum.core.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.ByteBuffer
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * AES-256-GCM vault. Provider, authentication and storage failures are explicit and
 * retryable: no failed operation ever deletes an alias, resets the vault, or rotates a key.
 * Only an explicit [remove] deletes an entry. Existing ciphertext uses the same wire format.
 */
class AndroidSecureStore internal constructor(
    private val backend: AndroidVaultBackend,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SecureStore {
    constructor(
        context: Context,
        prefsName: String = "velum_secure_encrypted",
        keyAlias: String = "dev.velum.secure_store_aes",
    ) : this(KeystoreVaultBackend(context.applicationContext, prefsName, keyAlias))

    override suspend fun putString(key: String, value: String) = access {
        val secret = backend.loadKey() ?: run {
            check(!backend.hasEntries()) { "Vault key is missing; existing data was preserved." }
            backend.createKey()
        }
        val encrypted = backend.encrypt(secret, value.toByteArray(Charsets.UTF_8))
        val payload = ByteBuffer.allocate(1 + encrypted.iv.size + encrypted.body.size)
            .put(encrypted.iv.size.toByte()).put(encrypted.iv).put(encrypted.body).array()
        currentCoroutineContext().ensureActive()
        check(backend.writeCiphertext(key, Base64.getEncoder().encodeToString(payload))) {
            "Unable to persist secure storage."
        }
    }

    override suspend fun getString(key: String): String? = access {
        val encoded = backend.readCiphertext(key) ?: return@access null
        val payload = Base64.getDecoder().decode(encoded)
        require(payload.isNotEmpty()) { "Invalid vault ciphertext." }
        val buffer = ByteBuffer.wrap(payload)
        val ivLength = buffer.get().toInt() and 0xff
        require(ivLength in 12..32 && buffer.remaining() > ivLength) { "Invalid vault ciphertext." }
        val iv = ByteArray(ivLength).also { buffer.get(it) }
        val body = ByteArray(buffer.remaining()).also { buffer.get(it) }
        // Reads must never create keys, even when the alias is absent.
        val secret = checkNotNull(backend.loadKey()) { "Vault key is missing; existing data was preserved." }
        backend.decrypt(secret, iv, body).toString(Charsets.UTF_8)
    }

    override suspend fun contains(key: String): Boolean = access { backend.readCiphertext(key) != null }

    override suspend fun remove(key: String) = access {
        currentCoroutineContext().ensureActive()
        check(backend.removeCiphertext(key)) { "Unable to remove the requested secure entry." }
    }

    private suspend fun <T> access(block: suspend () -> T): T = withContext(ioDispatcher) {
        // Serialize key creation and preference operations across instances in this process.
        vaultMutex.withLock {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                throw SecureStoreUnavailableException(failure)
            }
        }
    }

    private companion object {
        val vaultMutex = Mutex()
    }
}

/** Injectable Android boundary: tests use synthetic keys, ciphertext and failures only. */
internal interface AndroidVaultBackend {
    suspend fun readCiphertext(name: String): String?
    suspend fun hasEntries(): Boolean
    suspend fun writeCiphertext(name: String, value: String): Boolean
    suspend fun removeCiphertext(name: String): Boolean
    suspend fun loadKey(): SecretKey?
    suspend fun createKey(): SecretKey
    suspend fun encrypt(key: SecretKey, plain: ByteArray): VaultCiphertext
    suspend fun decrypt(key: SecretKey, iv: ByteArray, body: ByteArray): ByteArray
}

internal data class VaultCiphertext(val iv: ByteArray, val body: ByteArray)

private class KeystoreVaultBackend(
    private val context: Context,
    private val prefsName: String,
    private val keyAlias: String,
) : AndroidVaultBackend {
    private val prefs: SharedPreferences by lazy { context.getSharedPreferences(prefsName, Context.MODE_PRIVATE) }
    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    override suspend fun readCiphertext(name: String): String? = prefs.getString(name, null)
    override suspend fun hasEntries(): Boolean = prefs.all.isNotEmpty()
    override suspend fun writeCiphertext(name: String, value: String): Boolean = prefs.edit().putString(name, value).commit()
    override suspend fun removeCiphertext(name: String): Boolean = prefs.edit().remove(name).commit()

    override suspend fun loadKey(): SecretKey? {
        val store = keyStore()
        if (!store.containsAlias(keyAlias)) return null
        return checkNotNull((store.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry)?.secretKey) {
            "Vault key is unavailable; existing data was preserved."
        }
    }

    override suspend fun createKey(): SecretKey {
        // A provider race must not overwrite an alias created by another caller.
        check(!keyStore().containsAlias(keyAlias)) { "Vault key already exists; retry the operation." }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    override suspend fun encrypt(key: SecretKey, plain: ByteArray): VaultCiphertext {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return VaultCiphertext(cipher.iv, cipher.doFinal(plain))
    }

    override suspend fun decrypt(key: SecretKey, iv: ByteArray, body: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return cipher.doFinal(body)
    }
}
