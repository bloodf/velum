package dev.velum.ai.engine

import dev.velum.ai.local.LocalAsrRouter
import dev.velum.core.model.AiProviderSettings
import dev.velum.core.model.ComputeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals

class AiTaskRouterTest {
    private val fakeAsr = object : Transcriber {
        override val engineId = "fake-asr"
        override suspend fun transcribe(input: AudioInput, modelId: String) =
            TranscriptionResult(text = "", engineId = engineId, modelId = modelId)
    }
    private val apiAsr = object : Transcriber {
        override val engineId = "api-asr"
        override suspend fun transcribe(input: AudioInput, modelId: String) =
            TranscriptionResult(text = "", engineId = engineId, modelId = modelId)
    }
    private val fakeDiarizer = object : Diarizer {
        override val engineId = "fake-diar"
        override suspend fun diarize(input: AudioInput, modelId: String) =
            DiarizationResult(spans = emptyList(), engineId = engineId, modelId = modelId)
    }
    private val fakeLlm = object : LlmEngine {
        override val engineId = "fake-llm"
        override suspend fun complete(request: LlmRequest, modelId: String) =
            LlmResult(text = "", engineId = engineId, modelId = modelId)
    }

    @Test
    fun mixing_modes_resolves_per_task() {
        val settings = MutableStateFlow(
            AiProviderSettings(
                transcriptionMode = ComputeMode.Local,
                diarizationMode = ComputeMode.Api,
                summaryMode = ComputeMode.Api,
            ),
        )
        val router = AiTaskRouter(
            settings = settings,
            localTranscriber = fakeAsr,
            apiTranscriber = apiAsr,
            localDiarizer = fakeDiarizer,
            apiDiarizer = fakeDiarizer,
            localLlm = fakeLlm,
            apiLlm = fakeLlm,
        )
        assertEquals("fake-asr", router.transcriber().engineId)
        assertEquals(ComputeMode.Api, router.modeFor(AiTask.Diarization))
        assertEquals(ComputeMode.Api, router.modeFor(AiTask.Summary))
    }

    @Test
    fun local_asr_router_picks_parakeet_whisper_sensevoice() {
        assertEquals("parakeet-tdt-0.6b-v3-int8", LocalAsrRouter.chooseModelId("pt-BR"))
        assertEquals("parakeet-tdt-0.6b-v3-int8", LocalAsrRouter.chooseModelId("de"))
        assertEquals("sensevoice-small", LocalAsrRouter.chooseModelId("ja"))
        assertEquals("whisper-large-v3-turbo", LocalAsrRouter.chooseModelId("ar"))
        assertEquals("whisper-large-v3-turbo", LocalAsrRouter.chooseModelId("pt", mixedAudio = true))
    }
}
