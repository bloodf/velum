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
        requestTimeoutMillis = 120_000
        connectTimeoutMillis = 30_000
        socketTimeoutMillis = 120_000
    }
    defaultRequest {
        header(HttpHeaders.Accept, "application/json")
    }
}

/** Preferred entry point for apps — keeps Ktor types inside the `:ai` module. */
fun createOpenAiCompatibleClient(): OpenAiCompatibleClient =
    OpenAiCompatibleClient(createAiHttpClient())
