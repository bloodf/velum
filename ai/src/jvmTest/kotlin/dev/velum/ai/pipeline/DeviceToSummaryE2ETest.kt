package dev.velum.ai.pipeline

import dev.velum.ai.OpenAiCompatibleClient
import dev.velum.ai.audio.PcmWav
import dev.velum.ai.engine.AiTaskRouter
import dev.velum.ai.engine.JobProgress
import dev.velum.ai.local.LocalDiarizer
import dev.velum.ai.local.LocalLlmEngine
import dev.velum.ai.local.LocalTranscriber
import dev.velum.ai.local.ModelStoreDir
import dev.velum.ai.local.VoiceProfileStore
import dev.velum.ai.local.createLocalModelManager
import dev.velum.ai.openai.OpenAiDiarizer
import dev.velum.ai.openai.OpenAiLlmEngine
import dev.velum.ai.openai.OpenAiTranscriber
import dev.velum.ble.client.PlaudDeviceClient
import dev.velum.ble.crypto.JcaFileDecryptor
import dev.velum.ble.crypto.createFileDecryptor
import dev.velum.ble.crypto.createSessionCipher
import dev.velum.ble.crypto.rsaKeyPairToPem
import dev.velum.ble.fake.FakeBleTransport
import dev.velum.ble.fake.FakePlaudPeripheral
import dev.velum.core.data.DriverFactory
import dev.velum.core.data.VelumRepository
import dev.velum.core.data.createDatabase
import dev.velum.core.model.ComputeMode
import dev.velum.core.security.InMemorySecureStore
import dev.velum.core.settings.SettingsRepository
import dev.velum.protocol.DeviceIdentityKeys
import dev.velum.protocol.PlaudFileCrypto
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking

/**
 * Full CI path: fake Plaud peripheral → download → decrypt → API ASR/LLM (mocked) → markdown in DB.
 */
class DeviceToSummaryE2ETest {
    @Test
    fun device_download_transcribe_summarize_markdown() = runBlocking {
        val (publicPem, privatePem) = rsaKeyPairToPem()
        val keys = DeviceIdentityKeys(
            serialNumber = "881E2E01",
            serialSignature = ByteArray(256) { (it % 200).toByte() },
            publicKeyPem = publicPem,
            privateKeyPem = privatePem,
            bindToken = "e2e-user",
        )
        val cipher = createSessionCipher()
        val fileDecryptor = createFileDecryptor() as JcaFileDecryptor
        val plaintextAudio = ByteArray(3200) { (it % 64).toByte() }
        val encryptedFile = fileDecryptor.encryptFile(publicPem, plaintextAudio)

        val peripheral = FakePlaudPeripheral(
            cipher = cipher,
            clientKeys = keys,
            sessionFile = encryptedFile,
            requirePublicKey = true,
        )
        val ble = FakeBleTransport(peripheral)
        ble.startScan()
        ble.connect(ble.discoveredDevices.value.first().id)
        val client = PlaudDeviceClient(ble, cipher, keys)
        client.unlock()
        client.handshake()
        val sessions = client.listSessions(nowUnix = 1_700_000_222L)
        val downloaded = client.downloadSession(
            sessionId = sessions.entries[0].sessionId,
            sizeBytes = sessions.entries[0].sizeBytes,
        )
        val decrypted = PlaudFileCrypto.decrypt(downloaded, privatePem, fileDecryptor)
        assertTrue(decrypted.contentEquals(plaintextAudio))

        // Device pulls are raw s16le PCM; wrap as WAV so RecordingProcessor can stream-decode.
        val audioPath = Files.createTempFile("velum-e2e", ".wav")
        Files.write(audioPath, PcmWav.encode(decrypted, sampleRateHz = 16_000))

        val store = InMemorySecureStore()
        val settings = SettingsRepository(store)
        settings.load()
        settings.setBaseUrl("https://api.example.test/v1")
        settings.setApiKey("test-key")
        settings.setTranscriptionMode(ComputeMode.Api)
        settings.setDiarizationMode(ComputeMode.Local) // ModelNotReady → skip diarization
        settings.setSummaryMode(ComputeMode.Api)
        settings.setTranscriptionModel("whisper-1")
        settings.setChatModel("gpt-4o-mini")

        val mockEngine = MockEngine { request ->
            val path = request.url.encodedPath
            when {
                path.endsWith("/audio/transcriptions") -> respond(
                    content = """{"text":"Speaker 1: Bom dia.\nSpeaker 2: Vamos comecar."}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                path.endsWith("/chat/completions") -> respond(
                    content = """{"choices":[{"message":{"role":"assistant","content":"## Resumo\n\n- **Bom dia** alinhado\n- Proximos passos listados"}}]}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                else -> respond("unexpected ${request.url}", HttpStatusCode.NotFound)
            }
        }
        val http = HttpClient(mockEngine) {
            install(ContentNegotiation) { json(OpenAiCompatibleClient.defaultJson) }
        }
        val aiClient = OpenAiCompatibleClient(http)
        val modelRoot = ModelStoreDir(
            File.createTempFile("velum-models", "").also { it.delete(); it.mkdirs() }.absolutePath,
        )
        val modelManager = createLocalModelManager(storeRoot = modelRoot)
        val voices = VoiceProfileStore(modelRoot)
        val router = AiTaskRouter(
            settings = settings.settings,
            localTranscriber = LocalTranscriber(modelManager),
            apiTranscriber = OpenAiTranscriber(aiClient, settings),
            localDiarizer = LocalDiarizer(modelManager, voices),
            apiDiarizer = OpenAiDiarizer(),
            localLlm = LocalLlmEngine(modelManager),
            apiLlm = OpenAiLlmEngine(aiClient, settings),
        )

        val handle = createDatabase(DriverFactory())
        val repo = VelumRepository(handle)
        val recordingId = "rec-e2e-1"
        repo.insertRecording(
            id = recordingId,
            sessionId = sessions.entries[0].sessionId,
            folderId = null,
            title = "E2E Note Pro pull",
            source = "note_pro",
            deviceSerial = keys.serialNumber,
            startedAt = sessions.entries[0].sessionId,
            durationSeconds = 12,
            sizeBytes = decrypted.size.toLong(),
            localPath = audioPath.toAbsolutePath().toString(),
            transcript = null,
            summaryMarkdown = null,
            status = "imported",
        )

        val processor = RecordingProcessor(router, repo, settings)
        val last = processor.process(recordingId, templateId = null, retranscribe = true).last()
        if (last is JobProgress.Failed) {
            throw AssertionError("pipeline failed: ${last.message}")
        }
        assertIs<JobProgress.Succeeded>(last)

        val saved = repo.getRecording(recordingId)
        assertNotNull(saved)
        assertContains(saved.transcript.orEmpty(), "Bom dia")
        assertContains(saved.transcript.orEmpty(), "**Speaker 1:**")
        assertContains(saved.summary_markdown.orEmpty(), "Resumo")
        assertEquals("ready", saved.status)

        client.close()
        ble.disconnect()
        http.close()
    }
}
