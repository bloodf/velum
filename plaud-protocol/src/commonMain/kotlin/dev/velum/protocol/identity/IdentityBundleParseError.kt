package dev.velum.protocol.identity

/**
 * Typed parse failures for [IdentityBundleParser].
 *
 * Messages name fields and structural reasons only — never secret values.
 */
sealed class IdentityBundleParseError {
    /** Stable machine-oriented code for tests / UI mapping. */
    abstract val code: String

    /** Clear user-facing explanation without secret material. */
    abstract fun userMessage(): String

    override fun toString(): String = "IdentityBundleParseError.$code: ${userMessage()}"

    data object Empty : IdentityBundleParseError() {
        override val code: String = "empty"
        override fun userMessage(): String = "Identity bundle is empty"
    }

    data class Oversized(val maxChars: Int) : IdentityBundleParseError() {
        override val code: String = "oversized"
        override fun userMessage(): String =
            "Identity bundle exceeds the maximum size of $maxChars characters"
    }

    /**
     * Truncated JSON, garbage bytes, or otherwise unparseable structure.
     * Deliberately omits parser exception text (it may echo payload fragments).
     */
    data object Malformed : IdentityBundleParseError() {
        override val code: String = "malformed"
        override fun userMessage(): String =
            "Identity bundle is malformed or truncated"
    }

    data class UnsupportedVersion(val found: Int) : IdentityBundleParseError() {
        override val code: String = "unsupported_version"
        override fun userMessage(): String =
            "Unsupported identity bundle version $found " +
                "(expected ${IdentityBundleFormat.VERSION})"
    }

    data class MissingField(val path: String) : IdentityBundleParseError() {
        override val code: String = "missing_field"
        override fun userMessage(): String = "Missing required field: $path"
    }

    /**
     * @param detail structural reason only (e.g. "must decode to 256 bytes (got 16)").
     *               Must never include the rejected field value.
     */
    data class InvalidField(
        val path: String,
        val detail: String,
    ) : IdentityBundleParseError() {
        override val code: String = "invalid_field"
        override fun userMessage(): String = "Invalid field $path: $detail"
    }
}

sealed class IdentityBundleParseResult {
    data class Success(val bundle: IdentityBundle) : IdentityBundleParseResult() {
        override fun toString(): String = "IdentityBundleParseResult.Success(bundle=$bundle)"
    }

    data class Failure(val error: IdentityBundleParseError) : IdentityBundleParseResult() {
        override fun toString(): String = "IdentityBundleParseResult.Failure(error=$error)"
    }
}
