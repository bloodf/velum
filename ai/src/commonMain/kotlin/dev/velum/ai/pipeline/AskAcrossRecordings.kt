package dev.velum.ai.pipeline

import dev.velum.ai.MissingApiKeyException
import dev.velum.ai.engine.AiTaskRouter
import dev.velum.ai.engine.LlmRequest
import dev.velum.core.data.VelumRepository
import dev.velum.core.db.Recording
import dev.velum.core.model.ComputeMode
import dev.velum.core.search.FtsQuery
import dev.velum.core.settings.AiApiTask
import dev.velum.core.settings.SettingsRepository

/**
 * Ask a question across the whole library: FTS retrieval over transcripts/summaries,
 * then LLM answer (Local/API) with cited source moments.
 */
class AskAcrossRecordings(
    private val aiRouter: AiTaskRouter,
    private val repository: VelumRepository,
    private val settings: SettingsRepository,
) {
    data class Citation(
        val recordingId: String,
        val title: String,
        val offsetSeconds: Long,
        val timestampLabel: String,
        val snippet: String,
    )

    data class Answer(
        val markdown: String,
        val citations: List<Citation>,
        val matchedRecordingIds: List<String>,
    )

    /**
     * Retrieve relevant recordings via FTS, extract timed snippets, ask the LLM,
     * and return an answer that cites `[n]` → [Citation] entries.
     */
    suspend fun ask(
        question: String,
        limit: Int = 5,
        allRecordings: List<Recording>? = null,
    ): Answer {
        val q = question.trim()
        require(q.isNotEmpty()) { "Question is blank" }
        val cfg = settings.settings.value
        ensureApiKeyIfNeeded(cfg.summaryMode)

        val recordings = allRecordings ?: repository.getAllRecordings()
        val match = FtsQuery.fromUserInput(q)
        val ftsIds = if (match != null) {
            runCatching { repository.searchRecordingIdsFts(match) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        val ranked = rankRecordings(recordings, ftsIds, q).take(limit.coerceAtLeast(1))
        val citations = ranked.flatMap { rec ->
            extractSnippets(rec, q).take(2)
        }.take(8)

        if (citations.isEmpty()) {
            return Answer(
                markdown = "I could not find matching moments in your library for that question.",
                citations = emptyList(),
                matchedRecordingIds = emptyList(),
            )
        }

        val corpus = buildString {
            citations.forEachIndexed { index, c ->
                append('[').append(index + 1).append("] ")
                append(c.title).append(" @ ").append(c.timestampLabel).append('\n')
                append(c.snippet.trim()).append("\n\n")
            }
        }
        val system = buildString {
            append("You answer questions using ONLY the numbered source excerpts below. ")
            append("Reply in Markdown. Cite sources inline as [1], [2], etc. ")
            append("If the excerpts are insufficient, say what is missing. Never invent facts.\n\n")
            append("--- Sources ---\n")
            append(corpus)
        }
        val llm = aiRouter.llm()
        val model = when (cfg.summaryMode) {
            ComputeMode.Local -> cfg.localLlmModelId.orEmpty()
            ComputeMode.Api -> ""
        }
        val text = llm.complete(
            LlmRequest(
                systemPrompt = system,
                userPrompt = q,
                outputLanguage = cfg.outputLanguage,
                apiTask = AiApiTask.Ask,
            ),
            model,
        ).text
        return Answer(
            markdown = text.trim(),
            citations = citations,
            matchedRecordingIds = ranked.map { it.id },
        )
    }

    private fun rankRecordings(
        recordings: List<Recording>,
        ftsIds: List<String>,
        question: String,
    ): List<Recording> {
        val byId = recordings.associateBy { it.id }
        val fromFts = ftsIds.mapNotNull { byId[it] }
        if (fromFts.isNotEmpty()) return fromFts
        val tokens = tokenize(question)
        if (tokens.isEmpty()) return recordings.filter { !it.transcript.isNullOrBlank() }.take(5)
        return recordings
            .map { rec ->
                val hay = listOf(rec.title, rec.transcript.orEmpty(), rec.summary_markdown.orEmpty())
                    .joinToString("\n")
                    .lowercase()
                rec to tokens.count { hay.contains(it) }
            }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    private fun extractSnippets(rec: Recording, question: String): List<Citation> {
        val transcript = rec.transcript.orEmpty()
        if (transcript.isBlank()) {
            val summary = rec.summary_markdown.orEmpty()
            if (summary.isBlank()) return emptyList()
            return listOf(
                Citation(
                    recordingId = rec.id,
                    title = rec.title,
                    offsetSeconds = 0,
                    timestampLabel = "0:00",
                    snippet = summary.lineSequence().take(8).joinToString("\n"),
                ),
            )
        }
        val tokens = tokenize(question)
        val blocks = splitSpeakerBlocks(transcript)
        val scored = blocks.map { block ->
            val score = tokens.count { block.text.lowercase().contains(it) }
            block to score
        }.filter { it.second > 0 }
            .sortedByDescending { it.second }
            .ifEmpty { blocks.take(2).map { it to 1 } }
        return scored.take(3).map { (block, _) ->
            Citation(
                recordingId = rec.id,
                title = rec.title,
                offsetSeconds = block.offsetSeconds,
                timestampLabel = formatTimestamp(block.offsetSeconds),
                snippet = block.text.take(480),
            )
        }
    }

    private data class SpeakerBlock(val offsetSeconds: Long, val text: String)

    private fun splitSpeakerBlocks(transcript: String): List<SpeakerBlock> {
        val lines = transcript.lines()
        val blocks = mutableListOf<SpeakerBlock>()
        var currentOffset = 0L
        val buf = StringBuilder()
        fun flush() {
            if (buf.isNotBlank()) {
                blocks += SpeakerBlock(currentOffset, buf.toString().trim())
                buf.clear()
            }
        }
        val header = Regex("""^\*{0,2}([^*·\n]+)\*{0,2}\s*·\s*(\d{1,2}):(\d{2})(?::(\d{2}))?""")
        for (line in lines) {
            val m = header.find(line.trim())
            if (m != null) {
                flush()
                val mm = m.groupValues[2].toLongOrNull() ?: 0
                val ss = m.groupValues[3].toLongOrNull() ?: 0
                val hh = m.groupValues.getOrNull(4)?.toLongOrNull()
                currentOffset = if (hh != null) mm * 3600 + ss * 60 + hh else mm * 60 + ss
                buf.append(line).append('\n')
            } else {
                buf.append(line).append('\n')
            }
        }
        flush()
        return blocks.ifEmpty {
            listOf(SpeakerBlock(0, transcript.take(480)))
        }
    }

    private fun tokenize(question: String): List<String> =
        question.lowercase()
            .split(Regex("[^a-z0-9à-ü]+"))
            .map { it.trim() }
            .filter { it.length >= 3 }
            .distinct()

    private fun formatTimestamp(totalSeconds: Long): String {
        val m = totalSeconds / 60
        val s = totalSeconds % 60
        return "%d:%02d".format(m, s)
    }

    private suspend fun ensureApiKeyIfNeeded(mode: ComputeMode) {
        if (mode != ComputeMode.Api) return
        val resolved = settings.resolveApiModel(AiApiTask.Ask)
        val key = settings.getProfileApiKey(resolved.profileId)
        if (key.isNullOrBlank()) {
            throw MissingApiKeyException(
                "API key required for Ask across recordings. Add your OpenAI-compatible key in Settings → AI provider.",
            )
        }
    }
}
