package dev.velum.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApiErrorSanitizerTest {
    @Test
    fun sanitize_stripsSkKeysAndBearer() {
        val raw = "POST failed: Incorrect API key sk-secretABCDEF1234567890 via Bearer sk-otherkeyZZZZ9999"
        val clean = ApiErrorSanitizer.sanitize(raw)
        assertFalse(clean.contains("sk-secret", ignoreCase = true))
        assertFalse(clean.contains("sk-other", ignoreCase = true))
        assertTrue(clean.contains("sk-***"))
        assertTrue(clean.contains("Bearer ***"))
    }

    @Test
    fun httpFailure_statusOnly_noBody() {
        assertEquals(
            "GET /models failed: HTTP 401",
            ApiErrorSanitizer.httpFailure("GET /models", 401),
        )
        assertFalse(ApiErrorSanitizer.httpFailure("GET /models", 401).contains("sk-"))
    }
}
