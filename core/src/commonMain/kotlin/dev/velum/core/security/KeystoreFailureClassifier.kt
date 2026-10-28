package dev.velum.core.security

/**
 * Whether a Keystore failure should wipe vault ciphertext.
 *
 * Only [KeystoreVaultAction.ClearAndRegenerate] applies when the Keystore key is
 * permanently invalidated — the old key is gone and ciphertext sealed with it can
 * never be opened. Every other exception (transient crypto, auth, or Keystore
 * glitches) must keep all stored entries.
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
 * Accepts class names + messages so JVM unit tests can cover the policy without
 * Robolectric or the Android Keystore types.
 */
object KeystoreFailureClassifier {
    private const val PERMANENT_SIMPLE = "KeyPermanentlyInvalidatedException"
    private const val PERMANENT_MESSAGE = "Key permanently invalidated"

    /**
     * Classify a throwable chain described by [classNames] (simple or FQCN) and
     * [messages] (parallel cause-chain messages; may be shorter).
     */
    fun actionFor(classNames: List<String>, messages: List<String?> = emptyList()): KeystoreVaultAction {
        for (name in classNames) {
            val simple = name.substringAfterLast('.')
            if (simple == PERMANENT_SIMPLE) {
                return KeystoreVaultAction.ClearAndRegenerate
            }
        }
        for (msg in messages) {
            if (msg?.contains(PERMANENT_MESSAGE, ignoreCase = true) == true) {
                return KeystoreVaultAction.ClearAndRegenerate
            }
        }
        return KeystoreVaultAction.KeepEntries
    }

    /** Classify a live throwable by walking its cause chain. */
    fun actionFor(throwable: Throwable): KeystoreVaultAction {
        val names = ArrayList<String>(4)
        val messages = ArrayList<String?>(4)
        var cur: Throwable? = throwable
        while (cur != null) {
            names += cur::class.java.name
            messages += cur.message
            cur = cur.cause
        }
        return actionFor(names, messages)
    }
}
