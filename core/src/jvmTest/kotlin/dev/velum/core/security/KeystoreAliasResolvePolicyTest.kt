package dev.velum.core.security

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * JVM coverage for [AndroidSecureStore] alias resolution (#92):
 * transient getEntry failures keep the alias; permanent invalidation regenerates;
 * a missing alias creates one. Replacing an existing alias would permanently
 * strand every ciphertext entry.
 */
class KeystoreAliasResolvePolicyTest {

    @Test
    fun missing_alias_creates_one() {
        assertEquals(
            KeystoreAliasResolveAction.CreateMissing,
            KeystoreAliasResolvePolicy.decide(
                aliasPresent = false,
                entryObtained = false,
            ),
        )
    }

    @Test
    fun existing_entry_is_used() {
        assertEquals(
            KeystoreAliasResolveAction.UseExisting,
            KeystoreAliasResolvePolicy.decide(
                aliasPresent = true,
                entryObtained = true,
            ),
        )
    }

    @Test
    fun transient_getEntry_failure_keeps_alias() {
        val cases = listOf(
            listOf("java.lang.NullPointerException"),
            listOf("java.security.UnrecoverableKeyException"),
            listOf("java.security.InvalidKeyException"),
            listOf("java.security.KeyStoreException"),
            // Message-like phrase is irrelevant — class alone decides.
            listOf("java.security.ProviderException"),
        )
        for (names in cases) {
            assertEquals(
                KeystoreAliasResolveAction.PropagateTransient,
                KeystoreAliasResolvePolicy.decide(
                    aliasPresent = true,
                    entryObtained = false,
                    getEntryFailureClassNames = names,
                ),
                "expected PropagateTransient for $names",
            )
        }
        // Alias present, getEntry returned null without throwing — still keep.
        assertEquals(
            KeystoreAliasResolveAction.PropagateTransient,
            KeystoreAliasResolvePolicy.decide(
                aliasPresent = true,
                entryObtained = false,
            ),
        )
    }

    @Test
    fun permanent_invalidation_regenerates() {
        assertEquals(
            KeystoreAliasResolveAction.ClearAndRegenerate,
            KeystoreAliasResolvePolicy.decide(
                aliasPresent = true,
                entryObtained = false,
                getEntryFailureClassNames = listOf(
                    "android.security.keystore.KeyPermanentlyInvalidatedException",
                ),
            ),
        )
        assertEquals(
            KeystoreAliasResolveAction.ClearAndRegenerate,
            KeystoreAliasResolvePolicy.decide(
                aliasPresent = true,
                entryObtained = false,
                getEntryFailureClassNames = listOf(
                    "javax.crypto.IllegalBlockSizeException",
                    "android.security.keystore.KeyPermanentlyInvalidatedException",
                ),
            ),
        )
    }

    @Test
    fun decide_with_live_throwable_matches_class_names() {
        val npe = NullPointerException("invalid null input")
        assertEquals(
            KeystoreAliasResolveAction.PropagateTransient,
            KeystoreAliasResolvePolicy.decide(
                aliasPresent = true,
                entryObtained = false,
                getEntryFailure = npe,
            ),
        )
    }
}
