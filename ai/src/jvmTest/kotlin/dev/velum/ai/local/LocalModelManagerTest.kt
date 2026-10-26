package dev.velum.ai.local

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalModelManagerTest {
    @Test
    fun blocked_on_url_never_fakes_ready() = runTest {
        val root = ModelStoreDir(File.createTempFile("velum-models", "").also { it.delete(); it.mkdirs() }.absolutePath)
        val manager = LocalModelManager(
            storeRoot = root,
            httpClient = HttpClient(MockEngine { respond("") }),
            catalog = listOf(
                LocalModelSpec(
                    id = "no-url",
                    displayName = "No URL",
                    kind = LocalModelKind.Llm,
                    approxBytes = 1,
                    license = "Apache-2.0",
                    notes = "test",
                    languages = "en",
                    downloadUrl = null,
                ),
            ),
        )
        val states = manager.download("no-url").toList()
        assertEquals(LocalModelStatus.BlockedOnUrl, states.last().status)
        assertTrue(states.last().error?.contains("No download URL") == true)
    }

    @Test
    fun blocked_without_sha256() = runTest {
        val root = ModelStoreDir(File.createTempFile("velum-models", "").also { it.delete(); it.mkdirs() }.absolutePath)
        val manager = LocalModelManager(
            storeRoot = root,
            httpClient = HttpClient(MockEngine { respond(byteArrayOf(1)) }),
            catalog = listOf(
                LocalModelSpec(
                    id = "no-sha",
                    displayName = "No SHA",
                    kind = LocalModelKind.Asr,
                    approxBytes = 1,
                    license = "MIT",
                    notes = "test",
                    languages = "en",
                    downloadUrl = "https://example.test/x.bin",
                    archiveFileName = "x.bin",
                    sha256 = null,
                ),
            ),
        )
        assertEquals(LocalModelStatus.BlockedOnUrl, manager.stateFor("no-sha")?.status)
        val states = manager.download("no-sha").toList()
        assertEquals(LocalModelStatus.BlockedOnUrl, states.last().status)
        assertTrue(states.last().error?.contains("SHA-256") == true)
    }

    @Test
    fun download_writes_file_and_marks_ready() = runTest {
        val rootDir = File.createTempFile("velum-models", "").also { it.delete(); it.mkdirs() }
        val root = ModelStoreDir(rootDir.absolutePath)
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val engine = MockEngine { _ ->
            respond(
                content = payload,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/octet-stream"),
            )
        }
        val manager = LocalModelManager(
            storeRoot = root,
            httpClient = HttpClient(engine),
            catalog = listOf(
                LocalModelSpec(
                    id = "tiny",
                    displayName = "Tiny",
                    kind = LocalModelKind.Asr,
                    approxBytes = 5,
                    license = "MIT",
                    notes = "test",
                    languages = "en",
                    downloadUrl = "https://example.test/tiny.bin",
                    archiveFileName = "tiny.bin",
                    sha256 = sha256Hex(payload),
                ),
            ),
        )
        val states = manager.download("tiny").toList()
        assertEquals(LocalModelStatus.Ready, states.last().status, states.last().error)
        assertTrue(root.resolve("tiny").resolve("tiny.bin").exists())
        manager.delete("tiny")
        assertEquals(LocalModelStatus.NotDownloaded, manager.stateFor("tiny")?.status)
        assertTrue(!root.resolve("tiny").resolve("tiny.bin").exists())
    }

    @Test
    fun download_rejects_http_url() = runTest {
        val root = ModelStoreDir(File.createTempFile("velum-models", "").also { it.delete(); it.mkdirs() }.absolutePath)
        val payload = byteArrayOf(1)
        val manager = LocalModelManager(
            storeRoot = root,
            httpClient = HttpClient(MockEngine { respond(payload) }),
            catalog = listOf(
                LocalModelSpec(
                    id = "http-only",
                    displayName = "HTTP",
                    kind = LocalModelKind.Asr,
                    approxBytes = 1,
                    license = "MIT",
                    notes = "test",
                    languages = "en",
                    downloadUrl = "http://example.test/x.bin",
                    archiveFileName = "x.bin",
                    sha256 = sha256Hex(payload),
                ),
            ),
        )
        val states = manager.download("http-only").toList()
        assertEquals(LocalModelStatus.Failed, states.last().status)
        assertTrue(states.last().error?.contains("https://") == true)
    }
}
