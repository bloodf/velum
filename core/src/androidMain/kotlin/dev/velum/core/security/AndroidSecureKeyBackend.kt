package dev.velum.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Keystore / key-resolution seam for [AndroidSecureStore].
 *
 * Production uses [AndroidKeystoreBackend]. Tests inject a fake from the
 * androidUnitTest source set only — never ship fakes in production sources.
 */
internal interface AndroidSecureKeyBackend {
    /**
     * Resolve or mint the AES key. On permanent invalidation the backend invokes
     * [onClearVault] (store clears prefs + alias) then mints a replacement.
     */
    fun getOrCreateKey(onClearVault: () -> Unit): SecretKey

    /**
     * Non-destructive resolve for [SecureStore.read]: never create, delete, or
     * regenerate. Transient / permanent failures throw so the store can return
     * [SecureRead.Unreadable] while leaving prefs untouched.
     */
    fun peekExistingKeyForRead(): SecretKey

    fun containsAlias(): Boolean

    fun deleteAlias()
}

/**
 * Real Android Keystore-backed key provider (non-exportable AES-256).
 */
internal class AndroidKeystoreBackend(
    private val keyAlias: String,
) : AndroidSecureKeyBackend {
    override fun getOrCreateKey(onClearVault: () -> Unit): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val aliasPresent = keyStore.containsAlias(keyAlias)
        if (!aliasPresent) {
            return createKey()
        }
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
                onClearVault()
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

    override fun peekExistingKeyForRead(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val aliasPresent = keyStore.containsAlias(keyAlias)
        val entryResult = runCatching {
            if (!aliasPresent) null
            else keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry
        }
        val entry = entryResult.getOrNull()
        val failure = entryResult.exceptionOrNull()
        return when (
            KeystoreAliasResolvePolicy.decide(
                aliasPresent = aliasPresent,
                entryObtained = entry != null,
                getEntryFailure = failure,
            )
        ) {
            KeystoreAliasResolveAction.UseExisting ->
                entry!!.secretKey
            KeystoreAliasResolveAction.CreateMissing ->
                throw IllegalStateException("Keystore alias missing during SecureStore.read")
            KeystoreAliasResolveAction.ClearAndRegenerate -> {
                val t = failure
                    ?: IllegalStateException("Key permanently invalidated during SecureStore.read")
                Log.w(TAG, "Keystore permanently invalidated on read; leaving vault: ${t::class.java.name}")
                throw t
            }
            KeystoreAliasResolveAction.PropagateTransient -> {
                val t = failure
                    ?: IllegalStateException("Keystore alias present but SecretKeyEntry missing")
                Log.w(TAG, "Keystore getEntry failed on read; retaining alias: ${t::class.java.name}")
                throw t
            }
        }
    }

    override fun containsAlias(): Boolean {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return keyStore.containsAlias(keyAlias)
    }

    override fun deleteAlias() {
        runCatching {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (keyStore.containsAlias(keyAlias)) {
                keyStore.deleteEntry(keyAlias)
            }
        }.onFailure { t ->
            Log.w(TAG, "Failed to delete Keystore alias: ${t::class.java.name}")
        }
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
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        private const val TAG = "VelumSecureStore"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}
