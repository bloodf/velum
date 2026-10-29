package dev.velum.core.security

import java.security.SecureRandom
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Test-only software AES key backend for [AndroidSecureStore] Robolectric tests.
 * Lives in androidUnitTest — never referenced from production sources.
 */
internal class FakeAndroidSecureKeyBackend : AndroidSecureKeyBackend {
    private val lock = Any()
    private var key: SecretKey? = null
    private var aliasPresent: Boolean = false

    /** Counts how many times a new key is minted. */
    var mintCount: Int = 0
        private set

    /**
     * When non-null, [peekExistingKeyForRead] and [getOrCreateKey] (with alias
     * present) throw this throwable instead of returning the key.
     */
    @Volatile
    var peekFailure: Throwable? = null

    /** When true, [getOrCreateKey] also honors [peekFailure] after alias exists. */
    @Volatile
    var getOrCreateHonorsPeekFailure: Boolean = true

    override fun getOrCreateKey(onClearVault: () -> Unit): SecretKey = synchronized(lock) {
        val failure = peekFailure
        if (aliasPresent && failure != null && getOrCreateHonorsPeekFailure) {
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

    private fun mintNewKey(): SecretKey {
        val generator = KeyGenerator.getInstance("AES")
        generator.init(256, SecureRandom())
        val minted = generator.generateKey()
        key = minted
        aliasPresent = true
        mintCount++
        return minted
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
