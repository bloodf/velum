package dev.velum.core.security

/**
 * Decision for [AndroidSecureStore] when resolving the Keystore AES alias.
 *
 * Creating a replacement key while an alias still exists makes every stored
 * ciphertext permanently undecryptable — the same data loss as a vault wipe.
 * Only [ClearAndRegenerate] may delete the alias (and clear the vault).
 */
enum class KeystoreAliasResolveAction {
    /** Alias absent — mint a fresh Keystore key. */
    CreateMissing,

    /** Alias present and [KeyStore.SecretKeyEntry] obtained — use it. */
    UseExisting,

    /**
     * Alias present but the key is permanently invalidated — delete alias,
     * clear every ciphertext entry, mint a fresh key.
     */
    ClearAndRegenerate,

    /**
     * Alias present but [KeyStore.getEntry] failed transiently (including the
     * API 35 emulator NPE). Keep the alias and every entry; propagate the
     * failure so reads return null and writes fail until a later retry.
     */
    PropagateTransient,
}

/**
 * Pure policy for Keystore alias resolution — JVM-testable without Robolectric.
 *
 * [getEntryFailureClassNames] describe the throwable chain when [getEntry] threw;
 * leave empty when [getEntry] returned normally (possibly with a null / non-secret entry).
 */
object KeystoreAliasResolvePolicy {
    fun decide(
        aliasPresent: Boolean,
        entryObtained: Boolean,
        getEntryFailureClassNames: List<String> = emptyList(),
    ): KeystoreAliasResolveAction {
        if (!aliasPresent) return KeystoreAliasResolveAction.CreateMissing
        if (entryObtained) return KeystoreAliasResolveAction.UseExisting
        if (getEntryFailureClassNames.isNotEmpty()) {
            return when (KeystoreFailureClassifier.actionFor(getEntryFailureClassNames)) {
                KeystoreVaultAction.ClearAndRegenerate ->
                    KeystoreAliasResolveAction.ClearAndRegenerate
                KeystoreVaultAction.KeepEntries ->
                    KeystoreAliasResolveAction.PropagateTransient
            }
        }
        // Alias present, no SecretKeyEntry, no throw — do not replace the alias.
        return KeystoreAliasResolveAction.PropagateTransient
    }

    fun decide(
        aliasPresent: Boolean,
        entryObtained: Boolean,
        getEntryFailure: Throwable?,
    ): KeystoreAliasResolveAction {
        if (getEntryFailure == null) {
            return decide(aliasPresent = aliasPresent, entryObtained = entryObtained)
        }
        val names = ArrayList<String>(4)
        var cur: Throwable? = getEntryFailure
        while (cur != null) {
            names += cur::class.java.name
            cur = cur.cause
        }
        return decide(
            aliasPresent = aliasPresent,
            entryObtained = entryObtained,
            getEntryFailureClassNames = names,
        )
    }
}
