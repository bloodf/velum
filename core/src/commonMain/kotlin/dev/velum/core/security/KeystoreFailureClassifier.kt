package dev.velum.core.security

/**
 * Whether a Keystore failure should wipe vault ciphertext.
 *
 * Only [KeystoreVaultAction.ClearAndRegenerate] applies when the Keystore key is
 * permanently invalidated — the old key is gone and ciphertext sealed with it can
 * never be opened. Every other exception (transient crypto, auth, or Keystore
 * glitches) must keep all stored entries.
 *
 * Matching is by exception **class name only** (`KeyPermanentlyInvalidatedException`
 * anywhere in the cause chain). Message text is never consulted.
 */
enum class KeystoreVaultAction {
    /** Delete the Keystore alias, clear every ciphertext entry, mint a fresh key. */
    ClearAndRegenerate,

    /** Leave every stored entry untouched. */
    KeepEntries,
}

/**
 * Pure classifier for Android Keystore failures.
 *
 * Accepts class names (simple or FQCN) so JVM unit tests can cover the policy
 * without Robolectric or the Android Keystore types.
 */
object KeystoreFailureClassifier {
    private const val PERMANENT_SIMPLE = "KeyPermanentlyInvalidatedException"

    /**
     * Classify a throwable chain described by [classNames] (simple or FQCN).
     * Only [PERMANENT_SIMPLE] triggers a vault clear.
     */
    fun actionFor(classNames: List<String>): KeystoreVaultAction {
        for (name in classNames) {
            val simple = name.substringAfterLast('.')
            if (simple == PERMANENT_SIMPLE) {
                return KeystoreVaultAction.ClearAndRegenerate
            }
        }
        return KeystoreVaultAction.KeepEntries
    }

    /** Classify a live throwable by walking its cause chain (class names only). */
    fun actionFor(throwable: Throwable): KeystoreVaultAction {
        val names = ArrayList<String>(4)
        var cur: Throwable? = throwable
        while (cur != null) {
            names += cur::class.java.name
            cur = cur.cause
        }
        return actionFor(names)
    }
}
