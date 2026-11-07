package dev.velum.app.platform

import kotlin.test.Test
import kotlin.test.assertEquals

class ProviderCopyProcessTest {
    @Test fun api26And27UseOnlyOwnProcessLookup() {
        for (sdk in listOf(26, 27)) {
            assertEquals(ProviderCopyProcessRole.COPY, providerCopyProcessRole("app", sdk, { error("API28") }, { "app:provider_copy" }))
            assertEquals(ProviderCopyProcessRole.MAIN, providerCopyProcessRole("app", sdk, { error("API28") }, { "app" }))
            assertEquals(ProviderCopyProcessRole.UNKNOWN, providerCopyProcessRole("app", sdk, { error("API28") }, { null }))
        }
    }
    @Test fun api28AndLaterUsePublicNameAndRequireExactIdentity() {
        for (sdk in listOf(28, 34)) {
            assertEquals(ProviderCopyProcessRole.COPY, providerCopyProcessRole("app", sdk, { "app:provider_copy" }, { error("fallback") }))
            assertEquals(ProviderCopyProcessRole.MAIN, providerCopyProcessRole("app", sdk, { "app" }, { error("fallback") }))
            for (name in listOf(null, "other:provider_copy", "app:provider_copy_extra", "app:other")) {
                assertEquals(ProviderCopyProcessRole.UNKNOWN, providerCopyProcessRole("app", sdk, { name }, { error("fallback") }))
            }
        }
    }
    @Test fun lookupErrorsAreUnknown() {
        for (sdk in listOf(26, 28)) {
            assertEquals(ProviderCopyProcessRole.UNKNOWN, providerCopyProcessRole("app", sdk, { error("unavailable") }, { error("unavailable") }))
        }
    }
}
