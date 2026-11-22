package dev.velum.ai.local

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LocalModelInitializationTest {
    private val bytes = "verified weights".encodeToByteArray()
    private val spec = LocalModelSpec(
        "model", "Model", LocalModelKind.Llm, bytes.size.toLong(), "test", "", "en",
        "https://example.test/model.litertlm", sha256Hex(bytes), "model.litertlm",
    )

    @Test fun startup_is_pending_until_io_runs_and_checks_the_actual_bytes() = runTest {
        val root = ModelStoreDir(Files.createTempDirectory("velum-init").toString())
        val weights = root.resolve(spec.id).resolve(spec.archiveFileName)
        weights.writeBytes(bytes)
        val client = HttpClient(MockEngine { error("startup must stay offline") })
        try {
            val manager = LocalModelManager(root, client, listOf(spec), StandardTestDispatcher(testScheduler))
            assertFalse(manager.isReady(spec.id))
            assertEquals(null, manager.modelPath(spec.id))
            // Same-length corruption after construction must be observed by the scheduled scan.
            weights.writeBytes(ByteArray(bytes.size) { 1 })
            runCurrent()
            manager.awaitInitialization()
            assertEquals(LocalModelStatus.Failed, manager.stateFor(spec.id)?.status)
            assertTrue(weights.exists())
            weights.writeBytes(bytes)
            manager.refreshFromDisk().join()
            assertTrue(manager.isReady(spec.id))
        } finally {
            client.close()
            root.deleteRecursively()
        }
    }

    @Test fun download_waits_for_initialization_and_keeps_a_verified_install_offline() = runTest {
        val root = ModelStoreDir(Files.createTempDirectory("velum-init-ready").toString())
        root.resolve(spec.id).resolve(spec.archiveFileName).writeBytes(bytes)
        val client = HttpClient(MockEngine { error("verified install must not be downloaded") })
        try {
            val manager = LocalModelManager(root, client, listOf(spec), StandardTestDispatcher(testScheduler))
            val download = async { manager.download(spec.id).toList() }
            assertFalse(manager.isReady(spec.id))
            assertEquals(LocalModelStatus.Ready, download.await().last().status)
            manager.delete(spec.id)
            manager.refreshFromDisk().join()
            assertFalse(manager.isReady(spec.id))
            assertFalse(root.resolve(spec.id).exists())
        } finally {
            client.close()
            root.deleteRecursively()
        }
    }

    @Test fun refresh_during_download_waits_for_the_verified_commit() = runTest {
        val root = ModelStoreDir(Files.createTempDirectory("velum-init-race").toString())
        val requested = CompletableDeferred<Unit>()
        val response = CompletableDeferred<Unit>()
        val client = HttpClient(MockEngine {
            requested.complete(Unit)
            response.await()
            respond(bytes)
        })
        try {
            val manager = LocalModelManager(root, client, listOf(spec), StandardTestDispatcher(testScheduler))
            val download = async { manager.download(spec.id).toList() }
            requested.await()
            val refresh = manager.refreshFromDisk()
            runCurrent()
            assertFalse(refresh.isCompleted)
            assertEquals(LocalModelStatus.Downloading, manager.stateFor(spec.id)?.status)
            response.complete(Unit)
            assertEquals(LocalModelStatus.Ready, download.await().last().status)
            refresh.join()
            assertTrue(manager.isReady(spec.id))
            manager.delete(spec.id)
            assertFalse(manager.isReady(spec.id))
        } finally {
            client.close()
            root.deleteRecursively()
        }
    }
}
