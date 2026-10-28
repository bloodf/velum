package dev.velum.ai.openai

import dev.velum.ai.OpenAiCompatibleClient
import dev.velum.ai.audio.PcmWav
import dev.velum.ai.engine.AudioInput
import dev.velum.core.security.InMemorySecureStore
import dev.velum.core.settings.SettingsRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class OpenAiTranscriberUploadTest {
    @Test
    fun prepareUpload_wrapsRawPcmAsWav() = runTest {
        val settings = SettingsRepository(InMemorySecureStore()).also { it.load() }
        val transcriber = OpenAiTranscriber(
            client = OpenAiCompatibleClient(
                HttpClient(MockEngine { respond("{}", HttpStatusCode.OK) }) {
                    install(ContentNegotiation) { json(OpenAiCompatibleClient.defaultJson) }
                },
            ),
            settings = settings,
        )
        val pcm = ByteArray(32) { (it % 7).toByte() }
        val (name, bytes) = transcriber.prepareUpload(
            AudioInput(
                filename = "clip.ogg",
                bytes = pcm,
                sampleRateHz = 16_000,
            ),
        )
        assertEquals("clip.wav", name)
        assertTrue(PcmWav.looksLikeWav(bytes))
        assertEquals(44 + pcm.size, bytes.size)
    }

    @Test
    fun prepareUpload_passesThroughExistingWav() = runTest {
        val settings = SettingsRepository(InMemorySecureStore()).also { it.load() }
        val transcriber = OpenAiTranscriber(
            client = OpenAiCompatibleClient(
                HttpClient(MockEngine { respond("{}", HttpStatusCode.OK) }) {
                    install(ContentNegotiation) { json(OpenAiCompatibleClient.defaultJson) }
                },
            ),
            settings = settings,
        )
        val wav = PcmWav.encode(ByteArray(8), 16_000)
        val (name, bytes) = transcriber.prepareUpload(
            AudioInput(filename = "already.wav", bytes = wav, sampleRateHz = 16_000),
        )
        assertEquals("already.wav", name)
        assertTrue(bytes.contentEquals(wav))
    }

    @Test
    fun missingApiKey_mentionsSettingsNotAccount() {
        assertTrue(
            !dev.velum.ai.MissingApiKeyException().message!!.contains("Account", ignoreCase = false) ||
                dev.velum.ai.MissingApiKeyException().message!!.contains("Settings"),
        )
        assertTrue(dev.velum.ai.MissingApiKeyException().message!!.contains("Settings"))
        assertTrue(!dev.velum.ai.MissingApiKeyException().message!!.contains("Account settings"))
    }
}
