package dev.velum.ai.pipeline

import dev.velum.ai.engine.AiTaskRouter
import dev.velum.ai.engine.AudioInput
import dev.velum.ai.engine.DiarizationResult
import dev.velum.ai.engine.Diarizer
import dev.velum.ai.engine.LlmEngine
import dev.velum.ai.engine.LlmRequest
import dev.velum.ai.engine.LlmResult
import dev.velum.ai.engine.SpeakerSpan
import dev.velum.ai.engine.Transcriber
import dev.velum.ai.engine.TranscriptionResult
import dev.velum.ai.local.ModelStoreDir
import dev.velum.ai.local.VoiceProfileStore
import dev.velum.ai.local.createProfileCipher
import dev.velum.core.data.DriverFactory
import dev.velum.core.data.VelumRepository
import dev.velum.core.data.createDatabase
import dev.velum.core.model.ComputeMode
import dev.velum.core.security.InMemorySecureStore
import dev.velum.core.security.SecureStore
import dev.velum.core.security.SecureStoreUnavailableException
import dev.velum.core.settings.SettingsRepository
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

/**
 * #399: the speaker cache and the speaker rows are replaced together by RecordingProcessor, in that order, and a run
 * that fails before either was written leaves both of the previous run untouched.
 */
class SpeakerCacheOrderTest {
    private val oldEmbedding = FloatArray(8) { 1f }
    private val newS1 = FloatArray(8) { 2f }
    private val newS2 = FloatArray(8) { 3f }

    private class Setup(
        val repo: VelumRepository,
        val handle: dev.velum.core.data.DatabaseHandle,
        val processor: RecordingProcessor,
        val store: VoiceProfileStore,
        val dir: File,
        val diarizerBehaviour: MutableStateFlow<suspend () -> DiarizationResult>,
    ) {
        /** Makes the next write that matches [event] on [table] fail, the way a dying database would. */
        fun failWrites(event: String, table: String) {
            handle.driver.execute(
                null,
                "CREATE TRIGGER synthetic_fail BEFORE $event ON $table BEGIN SELECT RAISE(ABORT, 'synthetic'); END",
                0,
            )
        }
    }

    private fun le32(v: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
    private fun le16(v: Int) = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array()
    private fun wav(pcm: ByteArray): ByteArray {
        val fmt = "fmt ".toByteArray() + le32(16) + le16(1) + le16(1) + le32(16_000) + le32(32_000) + le16(2) + le16(16)
        val body = "WAVE".toByteArray() + fmt + "data".toByteArray() + le32(pcm.size) + pcm
        return "RIFF".toByteArray() + le32(body.size) + body
    }

    private suspend fun setup(id: String, secure: SecureStore? = null, keyless: Boolean = false): Setup {
        val handle = createDatabase(DriverFactory())
        val repo = VelumRepository(handle)
        val settings = SettingsRepository(InMemorySecureStore()).also {
            it.load()
            it.setTranscriptionMode(ComputeMode.Local)
            it.setDiarizationMode(ComputeMode.Local)
            it.setSummaryMode(ComputeMode.Local)
        }
        val behaviour = MutableStateFlow<suspend () -> DiarizationResult>({ error("unset") })
        val asr = object : Transcriber {
            override val engineId = "asr"
            override suspend fun transcribe(input: AudioInput, modelId: String) =
                TranscriptionResult(text = "hello", engineId = engineId, modelId = modelId)
        }
        val diar = object : Diarizer {
            override val engineId = "fake"
            override suspend fun diarize(input: AudioInput, modelId: String): DiarizationResult = behaviour.value()
        }
        val llm = object : LlmEngine {
            override val engineId = "noop"
            override suspend fun complete(request: LlmRequest, modelId: String) =
                LlmResult(text = "## ok", engineId = engineId, modelId = modelId)
        }
        val router = AiTaskRouter(
            settings = MutableStateFlow(settings.settings.value),
            localTranscriber = asr, apiTranscriber = asr, localDiarizer = diar, apiDiarizer = diar,
            localLlm = llm, apiLlm = llm,
        )
        val dir = File.createTempFile("velum-399", "").also { it.delete(); it.mkdirs() }
        val store = if (keyless) {
            VoiceProfileStore(ModelStoreDir(dir.absolutePath), secureStore = secure)
        } else {
            VoiceProfileStore(ModelStoreDir(dir.absolutePath), cipherOverride = createProfileCipher(ByteArray(32) { 9 }))
        }
        val audio = File.createTempFile("velum-399", ".wav").apply { writeBytes(wav(ByteArray(64_000))); deleteOnExit() }
        repo.insertRecording(
            id = id, sessionId = null, folderId = null, title = "T", source = "phone", deviceSerial = null,
            startedAt = 1, durationSeconds = 1, sizeBytes = audio.length(), localPath = audio.path, transcript = null,
            summaryMarkdown = null, status = "queued",
        )
        // The previous run: one speaker row labelled S1 and its cached embedding.
        repo.updateSpeakers(id, listOf(VelumRepository.SpeakerUpsert(id = "$id-S1", label = "S1", displayName = null, talkSeconds = 1)))
        if (!keyless) store.cacheSpeakerEmbeddings(id, mapOf("S1" to oldEmbedding))
        return Setup(repo, handle, RecordingProcessor(router, repo, settings, voiceProfiles = store), store, dir, behaviour)
    }

    private fun newRun(): DiarizationResult = DiarizationResult(
        spans = listOf(SpeakerSpan("S1", 0, 500), SpeakerSpan("S2", 500, 900)),
        engineId = "fake", modelId = "m",
        embeddings = mapOf("S1" to newS1, "S2" to newS2),
    )

    @Test
    fun aFinishedRunReplacesTheRowsAndTheCacheTogether() = runBlocking {
        val id = "rec-399-ok"
        val s = setup(id)
        s.diarizerBehaviour.value = { newRun() }
        s.processor.process(id, templateId = "velum.meeting").toList()
        assertEquals(setOf("S1", "S2"), s.repo.getSpeakers(id).map { it.label }.toSet())
        assertContentEquals(newS1, s.store.cachedClusterEmbedding(id, "S1"))
        assertContentEquals(newS2, s.store.cachedClusterEmbedding(id, "S2"))
    }

    @Test
    fun aRunStoppedAfterDiarizingButBeforeTheRowsLeavesBothOfThePreviousRunUntouched() = runBlocking {
        val id = "rec-399-abort"
        val s = setup(id)
        var reached = false
        s.diarizerBehaviour.value = {
            reached = true
            val result = newRun()
            // The run is stopped right after the diarizer produced its result and before anything is written.
            currentCoroutineContext()[Job]!!.cancel(CancellationException("stopped between diarize and the rows"))
            result
        }
        runCatching { s.processor.process(id, templateId = "velum.meeting").toList() }
        assertTrue(reached, "the run got as far as the diarizer")
        assertEquals(listOf("S1"), s.repo.getSpeakers(id).map { it.label }, "the previous run's rows")
        assertContentEquals(oldEmbedding, s.store.cachedClusterEmbedding(id, "S1"), "the previous run's cache")
        assertNull(s.store.cachedClusterEmbedding(id, "S2"), "nothing of the new run was cached")
    }

    @Test
    fun namingSpeakersDoesNotTouchTheCache() = runBlocking {
        val id = "rec-399-name"
        val s = setup(id)
        val naming = s.store.nameSpeakers(mapOf("S1" to newS1, "S2" to newS2), matchThreshold = 0.99f)
        assertTrue(naming.names.isEmpty() && !naming.unavailable)
        assertContentEquals(oldEmbedding, s.store.cachedClusterEmbedding(id, "S1"), "the diarizer's naming left the cache alone")
        assertNull(s.store.cachedClusterEmbedding(id, "S2"))
    }

    @Test
    fun aFailingSpeakerRowWriteLeavesTheOldRowsAndNoCachedVoice() = runBlocking {
        val id = "rec-399-rows"
        val s = setup(id)
        s.diarizerBehaviour.value = { newRun() }
        s.failWrites("INSERT", "speaker")
        runCatching { s.processor.process(id, templateId = "velum.meeting").toList() }
        assertEquals(listOf("S1"), s.repo.getSpeakers(id).map { it.label }, "the failed write left the previous rows")
        assertNull(s.store.cachedClusterEmbedding(id, "S1"), "no cached voice may sit next to rows it was not made for")
        assertNull(s.store.cachedClusterEmbedding(id, "S2"))
    }

    @Test
    fun aFailingTranscriptWriteLeavesNoCachedVoiceEither() = runBlocking {
        val id = "rec-399-transcript"
        val s = setup(id)
        s.diarizerBehaviour.value = { newRun() }
        s.failWrites("UPDATE OF transcript", "recording")
        runCatching { s.processor.process(id, templateId = "velum.meeting").toList() }
        assertNull(s.store.cachedClusterEmbedding(id, "S1"), "the old S1 voice must be gone before the transcript changes")
        assertEquals(listOf("S1"), s.repo.getSpeakers(id).map { it.label })
    }

    @Test
    fun withoutTheVoiceKeyTheOldCacheIsDroppedNotKeptBesideNewRows() = runBlocking {
        val id = "rec-399-nokey"
        val unavailable = object : SecureStore {
            override suspend fun putString(key: String, value: String) = throw SecureStoreUnavailableException(IllegalStateException("down"))
            override suspend fun getString(key: String): String? = throw SecureStoreUnavailableException(IllegalStateException("down"))
            override suspend fun remove(key: String) = throw SecureStoreUnavailableException(IllegalStateException("down"))
            override suspend fun contains(key: String): Boolean = throw SecureStoreUnavailableException(IllegalStateException("down"))
        }
        val s = setup(id, secure = unavailable, keyless = true)
        // An old cache written while the key still worked, in the same directory and with the same cipher.
        val cipher = createProfileCipher(ByteArray(32) { 9 })
        val working = VoiceProfileStore(ModelStoreDir(s.dir.absolutePath), cipherOverride = cipher)
        working.cacheSpeakerEmbeddings(id, mapOf("S1" to oldEmbedding))
        assertContentEquals(oldEmbedding, working.cachedClusterEmbedding(id, "S1"), "the old cache really exists")
        // The run ends with the key unavailable: caching fails, and the old cache must go.
        s.store.cacheSpeakerEmbeddings(id, mapOf("S1" to newS1, "S2" to newS2))
        assertNull(working.cachedClusterEmbedding(id, "S1"), "the previous run's voice survived beside new rows")
        assertNull(working.cachedClusterEmbedding(id, "S2"))
    }
}
