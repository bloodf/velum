package dev.velum.core.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest

class AiBaseUrlTest {
    @Test
    fun acceptsHttpsAndLoopbackHttp() {
        assertEquals(
            "https://api.openai.com/v1",
            AiBaseUrl.normalizeAndValidate("https://api.openai.com/v1/"),
        )
        assertEquals(
            "http://localhost:8080/v1",
            AiBaseUrl.normalizeAndValidate("http://localhost:8080/v1"),
        )
        assertEquals(
            "http://127.0.0.1/v1",
            AiBaseUrl.normalizeAndValidate("http://127.0.0.1/v1"),
        )
    }

    @Test
    fun rejectsCleartextNonLoopbackAndMetadata() {
        assertFailsWith<IllegalArgumentException> {
            AiBaseUrl.normalizeAndValidate("http://evil.example/v1")
        }
        assertFailsWith<IllegalArgumentException> {
            AiBaseUrl.normalizeAndValidate("https://169.254.169.254/latest/meta-data")
        }
        assertFailsWith<IllegalArgumentException> {
            AiBaseUrl.normalizeAndValidate("http://169.254.169.254/")
        }
    }

    @Test
    fun settingsRepository_setBaseUrl_validates() = runTest {
        val repo = SettingsRepository(dev.velum.core.security.InMemorySecureStore())
        assertFailsWith<IllegalArgumentException> {
            repo.setBaseUrl("http://169.254.169.254/v1")
        }
        repo.setBaseUrl("https://api.example.test/v1/")
        assertEquals("https://api.example.test/v1", repo.settings.value.baseUrl)
    }
}
