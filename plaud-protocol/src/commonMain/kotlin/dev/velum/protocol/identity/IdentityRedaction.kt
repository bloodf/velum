package dev.velum.protocol.identity

/**
 * Helpers so identity secrets never appear in logs, crash text, or test output.
 */
object IdentityRedaction {
    const val PLACEHOLDER: String = "[REDACTED]"

    /**
     * Secret substrings from [bundle] that must never appear in error text,
     * `toString()`, logs, or crash reports. Skips blank optional PEMs.
     */
    fun secretSamples(bundle: IdentityBundle): List<String> = buildList {
        add(bundle.serial)
        add(bundle.bindToken)
        add(IdentityBase64.encode(bundle.serialSignature))
        bundle.publicKeyPem?.takeIf { it.isNotBlank() }?.let { add(it) }
        bundle.privateKeyPem?.takeIf { it.isNotBlank() }?.let { add(it) }
    }

    /** True when [haystack] contains any secret sample from [bundle]. */
    fun containsSecret(haystack: String, bundle: IdentityBundle): Boolean =
        secretSamples(bundle).any { sample -> sample.isNotEmpty() && haystack.contains(sample) }
}
