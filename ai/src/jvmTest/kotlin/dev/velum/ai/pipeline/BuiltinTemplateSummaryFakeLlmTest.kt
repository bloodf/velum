package dev.velum.ai.pipeline

import dev.velum.ai.engine.AiTaskRouter
import dev.velum.ai.engine.AudioInput
import dev.velum.ai.engine.DiarizationResult
import dev.velum.ai.engine.Diarizer
import dev.velum.ai.engine.JobProgress
import dev.velum.ai.engine.LlmEngine
import dev.velum.ai.engine.LlmRequest
import dev.velum.ai.engine.LlmResult
import dev.velum.ai.engine.Transcriber
import dev.velum.ai.engine.TranscriptionResult
import dev.velum.core.data.DriverFactory
import dev.velum.core.data.VelumRepository
import dev.velum.core.data.createDatabase
import dev.velum.core.model.ComputeMode
import dev.velum.core.security.InMemorySecureStore
import dev.velum.core.settings.SettingsRepository
import dev.velum.core.templates.BuiltinTemplateCatalog
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking

/**
 * Each Velum-original built-in template must produce a Markdown summary via a fake LLM
 * that mirrors the section headings requested in the enhanced system prompt.
 *
 * Plaud catalog rows (1,500+) are validated by [dev.velum.core.templates.PlaudTemplateCatalogTest]
 * for schema/prompt wrapping — not exercised here (would dominate CI time).
 */
class BuiltinTemplateSummaryFakeLlmTest {
    @Test
    fun every_builtin_template_renders_summary_with_fake_llm() = runBlocking {
        val repo = VelumRepository(createDatabase(DriverFactory()))
        val store = InMemorySecureStore()
        val settings = SettingsRepository(store)
        settings.load()
        settings.setTranscriptionMode(ComputeMode.Local)
        settings.setDiarizationMode(ComputeMode.Local)
        settings.setSummaryMode(ComputeMode.Local)
        settings.setOutputLanguage("pt-BR")
        settings.setAudioLanguage("pt-BR")

        val captured = mutableListOf<LlmRequest>()
        val fakeLlm = object : LlmEngine {
            override val engineId = "fake-llm"
            override suspend fun complete(request: LlmRequest, modelId: String): LlmResult {
                captured += request
                val headings = Regex("##\\s+([^\\n]+)").findAll(request.systemPrompt)
                    .map { it.groupValues[1].trim() }
                    .filter { it.isNotBlank() }
                    .toList()
                val fromList = Regex("omit[^:]*:\\s*(.+)", RegexOption.IGNORE_CASE)
                    .find(request.systemPrompt)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.split(';')
                    ?.map { it.trim().trimEnd('.') }
                    ?.filter { it.isNotBlank() }
                    .orEmpty()
                val sections = headings.ifEmpty { fromList }.ifEmpty {
                    listOf("Visão geral", "Pontos principais")
                }
                val md = buildString {
                    sections.take(6).forEach { section ->
                        append("## ").append(section).append('\n')
                        append("- ponto derivado do transcript\n\n")
                    }
                }
                return LlmResult(text = md.trim(), engineId = engineId, modelId = modelId)
            }
        }
        val noopAsr = object : Transcriber {
            override val engineId = "noop-asr"
            override suspend fun transcribe(input: AudioInput, modelId: String) =
                TranscriptionResult(text = "", engineId = engineId, modelId = modelId)
        }
        val noopDiar = object : Diarizer {
            override val engineId = "noop-diar"
            override suspend fun diarize(input: AudioInput, modelId: String) =
                DiarizationResult(spans = emptyList(), engineId = engineId, modelId = modelId)
        }
        val router = AiTaskRouter(
            settings = MutableStateFlow(settings.settings.value),
            localTranscriber = noopAsr,
            apiTranscriber = noopAsr,
            localDiarizer = noopDiar,
            apiDiarizer = noopDiar,
            localLlm = fakeLlm,
            apiLlm = fakeLlm,
        )
        val processor = RecordingProcessor(router, repo, settings)

        val velumSpecs = BuiltinTemplateCatalog.All.filter { it.id.startsWith("velum.") && it.sectionsPtBr.isNotEmpty() }
        assertEquals(15, velumSpecs.size)
        assertTrue(BuiltinTemplateCatalog.All.size >= 1515)
        velumSpecs.forEach { spec ->
            captured.clear()
            val recordingId = "rec-tpl-${spec.id.replace('.', '-')}"
            repo.insertRecording(
                id = recordingId,
                sessionId = null,
                folderId = null,
                title = "Template probe ${spec.namePtBr}",
                source = "phone",
                deviceSerial = null,
                startedAt = 1_700_000_000L,
                durationSeconds = 60,
                sizeBytes = 0,
                localPath = null,
                transcript = SAMPLE_TRANSCRIPT,
                summaryMarkdown = null,
                status = "ready",
            )
            val progress = processor.process(
                recordingId = recordingId,
                templateId = spec.id,
                retranscribe = false,
            ).last()
            assertIs<JobProgress.Succeeded>(progress, spec.id)
            val summary = assertNotNull(repo.getRecording(recordingId)?.summary_markdown, spec.id)
            assertTrue(summary.contains("## "), "expected Markdown H2 for ${spec.id}: $summary")
            assertTrue(captured.isNotEmpty(), "LLM not called for ${spec.id}")
            val sys = captured.first().systemPrompt
            assertFalsePlaceholder(sys, spec.id)
            assertContains(sys, "Brazilian Portuguese")
            spec.sectionsPtBr.take(2).forEach { section ->
                assertTrue(
                    sys.contains(section) || summary.contains(section),
                    "section '$section' missing for ${spec.id}",
                )
            }
        }
    }

    private fun assertFalsePlaceholder(sys: String, id: String) {
        assertTrue(!sys.contains("{output_language}"), id)
        assertTrue(!sys.contains("{transcript}"), id)
    }

    companion object {
        private val SAMPLE_TRANSCRIPT = """
            **Ana** · 0:00
            Vamos alinhar o orçamento de 50 mil até sexta, 2026-11-07.

            **Bruno** · 0:18
            Concordo. Eu fico responsável pelo contrato e pela revisão jurídica.

            **Ana** · 0:35
            Decisão: seguir com o fornecedor local. Próximo passo: enviar proposta amanhã.
        """.trimIndent()
    }
}
