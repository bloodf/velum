package dev.velum.ai.pipeline

import dev.velum.core.data.TranscriptMarkers
import dev.velum.ai.MissingApiKeyException
import dev.velum.ai.engine.AiTaskRouter
import dev.velum.ai.engine.LlmRequest
import dev.velum.core.data.VelumRepository
import dev.velum.core.model.ComputeMode
import dev.velum.core.settings.AiApiTask
import dev.velum.core.settings.SettingsRepository

/**
 * Local Ask AI over a recording's transcript + summary. No Plaud ask endpoints.
 */
class AskAiChat(
    private val aiRouter: AiTaskRouter,
    private val repository: VelumRepository,
    private val settings: SettingsRepository,
) {
    data class Turn(val role: String, val content: String)

    suspend fun ask(
        recordingId: String,
        question: String,
        history: List<Turn> = emptyList(),
    ): String {
        val recording = repository.getRecording(recordingId)
            ?: throw IllegalArgumentException("Recording not found: $recordingId")
        val cfg = settings.settings.value
        val prefs = settings.preferences.value
        ensureApiKeyIfNeeded(cfg.summaryMode)

        val personalization = repository.personalizationPromptFragment(
            includeMemory = prefs.useSavedMemory,
        )
        val system = buildString {
            append("You answer questions about one recording note. ")
            append("Use only the transcript and summary below. Reply in Markdown.\n")
            if (personalization.isNotBlank()) {
                append(personalization).append('\n')
            }
            if (prefs.customInstructions.isNotBlank()) {
                append("Custom instructions: ").append(prefs.customInstructions).append('\n')
            }
            val budget = AskContextBudget.forMode(cfg.summaryMode)
            append("\n--- Transcript ---\n")
            append(
                AskContextBudget.transcriptFor(
                    TranscriptMarkers.spoken(recording.transcript).orEmpty(),
                    question,
                    budget.transcriptChars,
                ).ifBlank { "(empty)" },
            )
            append("\n\n--- Summary ---\n")
            append(AskContextBudget.clip(recording.summary_markdown.orEmpty(), budget.summaryChars).ifBlank { "(empty)" })
        }
        val historyBlock = AskContextBudget.recentHistory(
            history.map { "${it.role}: ${it.content}" },
            AskContextBudget.forMode(cfg.summaryMode).historyChars,
        )
        val user = buildString {
            if (historyBlock.isNotBlank()) {
                append("Prior turns:\n").append(historyBlock).append("\n\n")
            }
            append(question.trim())
        }
        val llm = aiRouter.llm()
        val model = when (cfg.summaryMode) {
            ComputeMode.Local -> cfg.localLlmModelId.orEmpty()
            ComputeMode.Api -> ""
        }
        return llm.complete(
            LlmRequest(
                systemPrompt = system,
                userPrompt = user,
                outputLanguage = cfg.outputLanguage,
                apiTask = AiApiTask.Ask,
            ),
            model,
        ).text
    }

    private suspend fun ensureApiKeyIfNeeded(mode: ComputeMode) {
        if (mode != ComputeMode.Api) return
        val resolved = settings.resolveApiModel(AiApiTask.Ask)
        val key = settings.getProfileApiKey(resolved.profileId)
        if (key.isNullOrBlank()) {
            throw MissingApiKeyException(
                "API key required for Ask AI. Add your OpenAI-compatible key in Settings → AI provider.",
            )
        }
    }
}

/**
 * Keeps an Ask AI prompt inside the model's context. The whole transcript used to go into the prompt: the on-device
 * LLM (sized like the summary map-reduce, about 4k tokens) failed on anything longer than a short meeting, and
 * history grew with every turn. A transcript that fits is sent whole; a longer one sends the paragraphs that share the
 * most words with the question, in their original order, marked as excerpts.
 */
internal object AskContextBudget {
    data class Budget(val transcriptChars: Int, val summaryChars: Int, val historyChars: Int)

    /** On-device: leaves room for the instructions, the question and the answer in about 4k tokens. */
    val LOCAL = Budget(transcriptChars = 8_000, summaryChars = 3_000, historyChars = 2_000)

    /** API models have far larger contexts; the bound only stops a multi-hour transcript from failing the request. */
    val API = Budget(transcriptChars = 200_000, summaryChars = 20_000, historyChars = 20_000)

    fun forMode(mode: ComputeMode): Budget = when (mode) {
        ComputeMode.Local -> LOCAL
        ComputeMode.Api -> API
    }

    const val EXCERPT_NOTE = "(Excerpts of a long transcript, chosen for this question.)"
    private const val GAP = "\n\n…\n\n"

    fun transcriptFor(transcript: String, question: String, maxChars: Int): String {
        if (transcript.length <= maxChars) return transcript
        val paragraphs = transcript.split(Regex("\n\\s*\n")).map { it.trim() }.filter { it.isNotEmpty() }
        val words = questionWords(question)
        fun score(p: String): Int { val text = p.lowercase(); return words.count { text.contains(it) } }
        // Best matches first; the earlier paragraph wins a tie, and without any match the transcript's start is kept.
        val ranked = paragraphs.indices.sortedWith(compareByDescending<Int> { score(paragraphs[it]) }.thenBy { it })
        val room = maxChars - EXCERPT_NOTE.length - 2
        val picked = mutableSetOf<Int>()
        var used = 0
        for (i in ranked) {
            val cost = paragraphs[i].length + GAP.length
            if (used + cost > room) continue
            picked += i
            used += cost
        }
        if (picked.isEmpty()) return EXCERPT_NOTE + "\n\n" + clip(paragraphs.firstOrNull().orEmpty(), room)
        return EXCERPT_NOTE + "\n\n" + picked.sorted().joinToString(GAP) { paragraphs[it] }
    }

    /** Lower-cased words of three or more letters/digits (short words match almost every paragraph). */
    private fun questionWords(question: String): Set<String> {
        val words = mutableSetOf<String>()
        val current = StringBuilder()
        for (ch in question.lowercase() + " ") {
            if (ch.isLetterOrDigit()) {
                current.append(ch)
            } else {
                if (current.length >= 3) words += current.toString()
                current.clear()
            }
        }
        return words
    }

    fun clip(text: String, maxChars: Int): String =
        if (text.length <= maxChars) text else text.take((maxChars - 1).coerceAtLeast(0)) + "…"

    /** The most recent turns that fit, oldest first. */
    fun recentHistory(turns: List<String>, maxChars: Int): String {
        val kept = ArrayDeque<String>()
        var used = 0
        for (turn in turns.asReversed()) {
            val cost = turn.length + 1
            if (used + cost > maxChars) break
            kept.addFirst(turn)
            used += cost
        }
        return kept.joinToString("\n")
    }
}

