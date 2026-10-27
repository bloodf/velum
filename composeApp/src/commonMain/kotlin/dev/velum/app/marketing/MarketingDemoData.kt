package dev.velum.app.marketing

import dev.velum.ai.pipeline.AskAcrossRecordings
import dev.velum.app.di.AppContainer
import dev.velum.core.data.VelumRepository

/**
 * Rich English fixtures for marketing / site screenshots (no Plaud branding).
 */
object MarketingDemoData {
    const val LIBRARY_SELECTED_ID = "mkt-sprint-review"
    const val LIVE_ID = "mkt-live-mic"
    const val IMPORT_HINT_ID = "mkt-field-notes"

    val AskPreview = AskAcrossRecordings.Answer(
        markdown = """
            The team kept the **Q4 marketing budget at ${'$'}120k** [1] and agreed Finance
            can unlock the reserve if pipeline hits **${'$'}2M** [1]. Separately, the hiring
            sync recommended a senior Android offer at **${'$'}185k base** [2].
        """.trimIndent(),
        citations = listOf(
            AskAcrossRecordings.Citation(
                recordingId = "mkt-budget-review",
                title = "Q4 budget review",
                offsetSeconds = 45,
                timestampLabel = "0:45",
                snippet = "Finance can unlock the reserve if pipeline hits 2 million.",
            ),
            AskAcrossRecordings.Citation(
                recordingId = "mkt-hiring-sync",
                title = "Hiring sync",
                offsetSeconds = 65,
                timestampLabel = "1:05",
                snippet = "Offer the senior Android role at 185k base.",
            ),
        ),
        matchedRecordingIds = listOf("mkt-budget-review", "mkt-hiring-sync"),
    )

    suspend fun seed(container: AppContainer) {
        val repo = container.repository
        // Drop Portuguese product-seed demos so marketing captures stay English-only.
        listOf("rec-demo-1", "rec-demo-2").forEach { runCatching { repo.deleteRecordingHard(it) } }
        val now = 1_730_000_000L
        seedRecording(
            repo,
            id = LIBRARY_SELECTED_ID,
            title = "Sprint review — Velum Android",
            source = "phone",
            startedAt = now - 3_600,
            durationSeconds = 42 * 60,
            status = "ready",
            transcript = SPRINT_TRANSCRIPT,
            summary = SPRINT_SUMMARY,
            localPath = "/demo/mkt-sprint-review.wav",
        )
        seedRecording(
            repo,
            id = "mkt-budget-review",
            title = "Q4 budget review",
            source = "phone",
            startedAt = now - 86_400,
            durationSeconds = 28 * 60,
            status = "ready",
            transcript = BUDGET_TRANSCRIPT,
            summary = BUDGET_SUMMARY,
            localPath = "/demo/mkt-budget-review.wav",
        )
        seedRecording(
            repo,
            id = "mkt-hiring-sync",
            title = "Hiring sync",
            source = "phone",
            startedAt = now - 172_800,
            durationSeconds = 18 * 60,
            status = "ready",
            transcript = HIRING_TRANSCRIPT,
            summary = """
                ## Summary
                Hiring sync for the senior Android opening.

                ## Decisions
                - Offer senior Android at ${'$'}185k base + 15% bonus target

                ## Action items
                - [ ] Send offer letter — **Sam Patel**, Wednesday
            """.trimIndent(),
            localPath = "/demo/mkt-hiring-sync.wav",
        )
        seedRecording(
            repo,
            id = IMPORT_HINT_ID,
            title = "Field notes — customer visit",
            source = "import",
            startedAt = now - 250_000,
            durationSeconds = 15 * 60,
            status = "ready",
            transcript = """
                **Casey Ng** · 0:20
                Customer wants offline-first capture with optional API override. They already tried cloud-only note tools and lost two weeks of field interviews when roaming dropped.

                **Alex Rivera** · 1:05
                We showed local Parakeet ASR plus the import hub for USB recorders. They asked whether Watch-folder sync can pick up m4a from their existing hardware.
            """.trimIndent(),
            summary = """
                ## Key points
                - Offline-first is non-negotiable for field teams
                - Import hub covers USB / SD / share-sheet audio

                ## Action items
                - [ ] Demo watched-folder sync on their Windows laptop — **Alex Rivera**
            """.trimIndent(),
            localPath = "/demo/mkt-field-notes.wav",
        )
        seedRecording(
            repo,
            id = LIVE_ID,
            title = "Product standup",
            source = "phone",
            startedAt = now - 95,
            durationSeconds = 95,
            status = "recording",
            transcript = LIVE_TRANSCRIPT,
            summary = null,
        )
        repo.updateSpeakers(
            LIBRARY_SELECTED_ID,
            listOf(
                VelumRepository.SpeakerUpsert("mkt-s1", "S1", "Alex Rivera", 620),
                VelumRepository.SpeakerUpsert("mkt-s2", "S2", "Jordan Lee", 540),
                VelumRepository.SpeakerUpsert("mkt-s3", "S3", "Sam Patel", 410),
            ),
        )
    }

    private suspend fun seedRecording(
        repo: VelumRepository,
        id: String,
        title: String,
        source: String,
        startedAt: Long,
        durationSeconds: Long,
        status: String,
        transcript: String?,
        summary: String?,
        localPath: String? = null,
    ) {
        repo.deleteRecordingHard(id)
        repo.insertRecording(
            id = id,
            sessionId = startedAt,
            folderId = "folder-meetings",
            title = title,
            source = source,
            deviceSerial = null,
            startedAt = startedAt,
            durationSeconds = durationSeconds,
            sizeBytes = durationSeconds * 32_000,
            localPath = localPath,
            transcript = transcript,
            summaryMarkdown = summary,
            status = status,
            createdAt = startedAt,
        )
    }

    private val SPRINT_TRANSCRIPT = """
        **Alex Rivera** · 0:00
        Good morning. Let's walk the Android sprint: local transcription, template summaries, and the new Ask across recordings flow. I want us to leave with owners and dates, not just status.

        **Jordan Lee** · 0:22
        Parakeet ASR is stable on mid-range phones. On the Pixel 7a we average about 0.4× realtime for a forty-minute meeting. Diarization with 3D-Speaker is naming returning speakers correctly after two enrollments — Heitor and Marina both matched in yesterday's standup.

        **Sam Patel** · 0:48
        Summary quality jumped once we treated prompt templates as skeletons and outline templates as instructions. Fake LLM coverage in CI still runs the fifteen Velum originals only so the suite stays fast; the curated catalog loads lazily from assets.

        **Alex Rivera** · 1:15
        Decision: ship the adaptive list–detail library on desktop and large tablets this week. Action for Jordan — land WindowSizeClass panes by Friday. Sam owns citation chips in Ask across so tapping a source jumps to the transcript timestamp.

        **Jordan Lee** · 1:42
        Also flag thermal pauses during long mic captures. WorkManager already resumes when the device cools; we should verify a ninety-minute session before release. I'll add a Roborazzi fixture for the live waveform pane so marketing can reuse it.

        **Sam Patel** · 2:10
        One more note on Ask across: FTS ranks transcripts first, then we pack the top snippets into the LLM system prompt with [n] markers. Local Gemma and the API path share the same citation schema so the UI stays identical.
    """.trimIndent()

    private val SPRINT_SUMMARY = """
        ## Summary
        Sprint review covering on-device ASR, speaker labeling, and the adaptive desktop library.

        ## Key points
        - Parakeet ASR is reliable on mid-range Android devices
        - 3D-Speaker enrollments label returning speakers automatically
        - Prompt vs outline template semantics improved summary structure

        ## Decisions
        - Ship adaptive list–detail for desktop / large tablets this week
        - Keep Fake LLM CI on Velum originals only (not the full template catalog)

        ## Action items
        - [ ] Land WindowSizeClass library panes — **Jordan Lee**, Friday
        - [ ] Citation chips for Ask across recordings — **Sam Patel**, Friday
        - [ ] Verify thermal pause / resume on a 90-minute mic capture — **Alex Rivera**
    """.trimIndent()

    private val BUDGET_TRANSCRIPT = """
        **Alex Rivera** · 0:12
        We agreed the marketing budget stays at 120 thousand for Q4. That covers paid social, two webinars, and the conference booth refresh.

        **Jordan Lee** · 0:45
        Finance can unlock the reserve if pipeline hits 2 million. Until then we hold creative production at the current burn rate.

        **Sam Patel** · 1:18
        I'll update the forecast sheet tonight so Ask across can cite the same numbers from this transcript.
    """.trimIndent()

    private val BUDGET_SUMMARY = """
        ## Summary
        Q4 budget alignment for marketing and reserve unlock criteria.

        ## Key points
        - Paid social, webinars, and booth refresh covered by the ${'$'}120k envelope
        - Creative production stays at current burn until reserve unlock

        ## Decisions
        - Marketing budget remains ${'$'}120k for Q4
        - Reserve unlock if pipeline reaches ${'$'}2M

        ## Action items
        - [ ] Update forecast sheet — **Sam Patel**, tonight
    """.trimIndent()

    private val HIRING_TRANSCRIPT = """
        **Sam Patel** · 0:40
        We reviewed three Android candidates this week. Two were mid-level; one has deep Compose Multiplatform experience.

        **Alex Rivera** · 1:05
        Offer the senior Android role at 185k base with a fifteen percent bonus target. Loop People Ops before Thursday so we do not lose them to another offer.
    """.trimIndent()

    private val LIVE_TRANSCRIPT = """
        **Alex Rivera** · 0:12
        Capturing from the phone mic — we'll keep this standup short and focus on shipping the adaptive library panes.

        **Jordan Lee** · 0:28
        Adaptive panes are rendering at 1440 by 900. Waveform and timer look clean on the desktop target; I'll mirror the same LiveWaveform on phone.

        **Sam Patel** · 0:41
        Next I'll wire citation taps to jump into the transcript at the source timestamp so Ask across feels like one continuous flow.
    """.trimIndent()
}
