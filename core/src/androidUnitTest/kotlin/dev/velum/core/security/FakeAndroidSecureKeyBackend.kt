package dev.velum.core.security

import java.security.InvalidAlgorithmParameterException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Test-only software AES key backend for [AndroidSecureStore] Robolectric tests.
 * Lives in androidUnitTest — never referenced from production sources.
 *
 * Mirrors AndroidKeyStore randomized-encryption: encrypt must go through
 * [initEncrypt] (no caller IV). [encryptWithCallerProvidedIv] throws the same
 * [InvalidAlgorithmParameterException] Keystore would raise, so a regression that
 * bypasses [initEncrypt] is caught when [encryptInitCount] stays at 0.
 */
internal class FakeAndroidSecureKeyBackend : AndroidSecureKeyBackend {
    private val lock = Any()
    private var key: SecretKey? = null
    private var aliasPresent: Boolean = false

    /** Counts how many times a new key is minted. */
    var mintCount: Int = 0
        private set

    /** Counts [initEncrypt] calls — encrypt must use the provider IV path. */
    var encryptInitCount: Int = 0
        private set

    /**
     * When non-null, [peekExistingKeyForRead] and [getOrCreateKey] (with alias
     * present) throw this throwable instead of returning the key.
     */
    @Volatile
    var peekFailure: Throwable? = null

    override fun getOrCreateKey(onClearVault: () -> Unit): SecretKey = synchronized(lock) {
        val failure = peekFailure
        if (aliasPresent && failure != null) {
            when (KeystoreFailureClassifier.actionFor(failure)) {
                KeystoreVaultAction.ClearAndRegenerate -> {
                    onClearVault()
                    return mintNewKey()
                }
                KeystoreVaultAction.KeepEntries -> throw failure
            }
        }
        if (!aliasPresent) {
            return mintNewKey()
        }
        return key!!
    }

    override fun peekExistingKeyForRead(): SecretKey = synchronized(lock) {
        if (!aliasPresent) {
            throw IllegalStateException("Keystore alias missing during SecureStore.read")
        }
        peekFailure?.let { throw it }
        return key!!
    }

    override fun containsAlias(): Boolean = synchronized(lock) { aliasPresent }

    override fun deleteAlias() = synchronized(lock) {
        aliasPresent = false
        key = null
    }

    override fun initEncrypt(cipher: Cipher, key: SecretKey): ByteArray {
        // Same contract as AndroidKeystoreBackend: no caller IV.
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        require(iv != null && iv.size == IV_BYTES) {
            "Encrypt IV must be $IV_BYTES bytes"
        }
        encryptInitCount++
        return iv
    }

    /**
     * Probe that mirrors AndroidKeyStore rejecting a caller-provided IV on
     * encrypt when randomized encryption is required. Software AES would accept
     * [GCMParameterSpec]; this fake throws like Keystore instead.
     */
    @Suppress("UNUSED_PARAMETER")
    fun encryptWithCallerProvidedIv(key: SecretKey, iv: ByteArray) {
        require(iv.size == IV_BYTES)
        throw InvalidAlgorithmParameterException("Caller-provided IV not permitted")
    }

    private fun mintNewKey(): SecretKey {
        val generator = KeyGenerator.getInstance("AES")
        generator.init(256, SecureRandom())
        val minted = generator.generateKey()
        key = minted
        aliasPresent = true
        mintCount++
        return minted
    }

    private companion object {
        private const val IV_BYTES = 12
    }
}

/**
 * Throwable whose simple name matches Android's permanent-invalidation type so
 * [KeystoreFailureClassifier] classifies it as [KeystoreVaultAction.ClearAndRegenerate]
 * without pulling Keystore types into the fake.
 */
internal class KeyPermanentlyInvalidatedException(
    message: String = "Key permanently invalidated",
) : Exception(message)
