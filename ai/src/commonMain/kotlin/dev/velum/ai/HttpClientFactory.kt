package dev.velum.ai

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json

expect fun createPlatformHttpClient(config: HttpClientConfig<*>.() -> Unit = {}): HttpClient

fun createAiHttpClient(): HttpClient = createPlatformHttpClient {
    expectSuccess = false
    install(ContentNegotiation) { json(OpenAiCompatibleClient.defaultJson) }
    install(HttpTimeout) {
        // Long map-reduce / windowed ASR chat+transcription calls.
        requestTimeoutMillis = 600_000
        connectTimeoutMillis = 30_000
        socketTimeoutMillis = 600_000
    }
    defaultRequest {
        header(HttpHeaders.Accept, "application/json")
    }
}

/**
 * Large local-model archives (hundreds of MB). No request timeout — only connect
 * bound — so Parakeet / Whisper turbo / Gemma downloads are not killed at 120s.
 */
fun createDownloadHttpClient(): HttpClient = createPlatformHttpClient {
    expectSuccess = false
    install(HttpTimeout) {
        // Multi-hour model archives; null = no timeout in Ktor HttpTimeout plugin.
        requestTimeoutMillis = null
        connectTimeoutMillis = 60_000
        socketTimeoutMillis = null
    }
}

/** Preferred entry point for apps — keeps Ktor types inside the `:ai` module. */
fun createOpenAiCompatibleClient(): OpenAiCompatibleClient =
    OpenAiCompatibleClient(createAiHttpClient())
