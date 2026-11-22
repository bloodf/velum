package dev.velum.app.marketing

import dev.velum.ai.pipeline.AskAcrossRecordings
import dev.velum.app.di.AppContainer
import dev.velum.core.data.VelumRepository

/**
 * Rich English fixtures for marketing / site screenshots (no Plaud branding).
 */
object MarketingDemoData {
    const val LIBRARY_SELECTED_ID = "mkt-product-sync"
    const val LIVE_ID = "mkt-live-mic"
    const val IMPORT_HINT_ID = "mkt-customer-interview"

    val AskPreview = AskAcrossRecordings.Answer(
        markdown = """
            The team kept the **Q4 marketing budget at ${'$'}120k** [1] and agreed Finance
            can release the reserve if the pipeline reaches **${'$'}2M** [1]. In the hiring
            sync, you decided to offer the senior product designer role at **${'$'}165k base** [2].
        """.trimIndent(),
        citations = listOf(
            AskAcrossRecordings.Citation(
                recordingId = "mkt-budget-review",
                title = "Q4 budget review",
                offsetSeconds = 45,
                timestampLabel = "0:45",
                snippet = "Finance can release the reserve if the pipeline reaches 2 million.",
            ),
            AskAcrossRecordings.Citation(
                recordingId = "mkt-hiring-sync",
                title = "Hiring sync: senior designer",
                offsetSeconds = 65,
                timestampLabel = "1:05",
                snippet = "Let's offer 165 base, with the usual bonus target.",
            ),
        ),
        matchedRecordingIds = listOf("mkt-budget-review", "mkt-hiring-sync"),
    )

    private class Demo(
        val id: String,
        val title: String,
        val source: String,
        /** How long before the seed's "now" it started, in minutes (varied, so the list shows real-looking times). */
        val minutesAgo: Long,
        val minutes: Long,
        val transcript: String?,
        val summary: String?,
        val speakers: List<String> = emptyList(),
        val status: String = "ready",
    )

    /**
     * Seeds ten finished recordings and one in progress, with people, transcripts and notes from ordinary work and
     * study (no product or engineering jargon: these end up on the website). [audioPathFor] returns a local audio file
     * of the given length for a recording, so players show a working transport instead of "audio missing".
     */
    suspend fun seed(container: AppContainer, audioPathFor: ((id: String, seconds: Long) -> String)? = null) {
        val repo = container.repository
        // Drop Portuguese product-seed demos so marketing captures stay English-only.
        listOf("rec-demo-1", "rec-demo-2").forEach { runCatching { repo.deleteRecordingHard(it) } }
        // Sunday 2024-10-27 17:00 UTC: the screenshot tests pin the time zone to UTC, so the list shows daytime clock times.
        val now = 1_730_048_400L
        for (demo in demos) {
            val seconds = if (demo.id == LIVE_ID) 95 else demo.minutes * 60
            seedRecording(
                repo,
                id = demo.id,
                title = demo.title,
                source = demo.source,
                startedAt = now - demo.minutesAgo * 60,
                durationSeconds = seconds,
                status = demo.status,
                transcript = demo.transcript,
                summary = demo.summary,
                localPath = if (demo.id == LIVE_ID) null else audioPathFor?.invoke(demo.id, seconds) ?: "/demo/${demo.id}.wav",
            )
            if (demo.speakers.isNotEmpty()) {
                repo.updateSpeakers(
                    demo.id,
                    demo.speakers.mapIndexed { i, name ->
                        VelumRepository.SpeakerUpsert("${demo.id}-s${i + 1}", "S${i + 1}", name, (demo.minutes * 60 / demo.speakers.size) - i * 40L)
                    },
                )
            }
        }
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

    private val demos = listOf(
        Demo(
            id = LIVE_ID,
            title = "Design critique",
            source = "phone",
            minutesAgo = 2,
            minutes = 2,
            status = "recording",
            transcript = """
                **Maya Chen** · 0:12
                Let's start with the new onboarding screens. I'd like one round of reactions before we talk about copy.

                **Jordan Lee** · 0:28
                The first screen works. The second asks for too much at once; people skip it when they see four fields.

                **Maya Chen** · 0:41
                Agreed. Let's split it and move the reminder settings to after the first recording.
            """.trimIndent(),
            summary = null,
        ),
        Demo(
            id = LIBRARY_SELECTED_ID,
            title = "Weekly product sync",
            source = "phone",
            minutesAgo = 74,
            minutes = 42,
            speakers = listOf("Alex Rivera", "Jordan Lee", "Sam Patel"),
            transcript = """
                **Alex Rivera** · 0:00
                Good morning, everyone. Three things today: the checkout redesign, the spring launch date, and what we heard from the beta group. I'd like us to leave with owners and dates, not just status.

                **Jordan Lee** · 0:22
                The new checkout is ready for review. In the beta, people finished a purchase in about half the time, and the number of abandoned carts on mobile went down by almost a third.

                **Sam Patel** · 0:48
                The feedback was mostly positive. The one recurring complaint is the delivery date: people want to see it before they enter their address, not after.

                **Alex Rivera** · 1:15
                Decision: we launch the new checkout on March 18th, together with the spring collection. Jordan, can you show the delivery estimate on the cart page by Friday? Sam owns the announcement email.

                **Jordan Lee** · 1:42
                Yes. I'll also check the gift-card flow with two customers before we freeze the design, so we don't ship a surprise.

                **Sam Patel** · 2:10
                For the email, I'll lead with the faster checkout and keep the spring collection as the second story. I'll share a draft on Wednesday.
            """.trimIndent(),
            summary = """
                ## Summary
                Weekly sync on the checkout redesign, the spring launch date, and beta feedback.

                ## Key points
                - The new checkout halved purchase time for beta customers
                - Abandoned mobile carts dropped by almost a third
                - Customers want the delivery date before entering their address

                ## Decisions
                - Launch the new checkout on March 18 with the spring collection
                - Announcement email leads with the faster checkout

                ## Action items
                - [ ] Show the delivery estimate on the cart page — **Jordan Lee**, Friday
                - [ ] Test the gift-card flow with two customers — **Jordan Lee**
                - [ ] Draft the announcement email — **Sam Patel**, Wednesday
            """.trimIndent(),
        ),
        Demo(
            id = "mkt-budget-review",
            title = "Q4 budget review",
            source = "phone",
            minutesAgo = 1_381,
            minutes = 28,
            speakers = listOf("Alex Rivera", "Priya Nair", "Sam Patel"),
            transcript = """
                **Alex Rivera** · 0:12
                We agreed the marketing budget stays at 120 thousand for Q4. That covers paid social, two webinars, and the trade show booth.

                **Priya Nair** · 0:45
                Finance can release the reserve if the pipeline reaches 2 million. Until then, we keep creative production at the current pace.

                **Sam Patel** · 1:18
                I'll update the forecast sheet tonight and send everyone the link.
            """.trimIndent(),
            summary = """
                ## Summary
                Q4 budget alignment for marketing and the conditions for releasing the reserve.

                ## Key points
                - Paid social, webinars, and the trade show booth fit in the ${'$'}120k budget
                - Creative production stays at the current pace until the reserve is released

                ## Decisions
                - Marketing budget remains ${'$'}120k for Q4
                - Reserve is released if the pipeline reaches ${'$'}2M

                ## Action items
                - [ ] Update and share the forecast sheet — **Sam Patel**, tonight
            """.trimIndent(),
        ),
        Demo(
            id = "mkt-hiring-sync",
            title = "Hiring sync: senior designer",
            source = "phone",
            minutesAgo = 2_917,
            minutes = 18,
            speakers = listOf("Maya Chen", "Alex Rivera"),
            transcript = """
                **Maya Chen** · 0:40
                We met three candidates this week. Two are strong on visual design; Lena also ran research at her last company, which is what we're missing.

                **Alex Rivera** · 1:05
                Then let's offer Lena the senior product designer role. Let's offer 165 base, with the usual bonus target, and loop in People Ops before Thursday so we don't lose her.
            """.trimIndent(),
            summary = """
                ## Summary
                Hiring decision for the senior product designer opening.

                ## Decisions
                - Offer Lena the role at ${'$'}165k base plus the standard bonus target

                ## Action items
                - [ ] Send the offer letter — **Maya Chen**, Wednesday
                - [ ] Brief People Ops — **Alex Rivera**, before Thursday
            """.trimIndent(),
        ),
        Demo(
            id = IMPORT_HINT_ID,
            title = "Customer interview: Dana, Whitfield Dental",
            source = "import",
            minutesAgo = 4_196,
            minutes = 35,
            speakers = listOf("Casey Ng", "Dana Whitfield"),
            transcript = """
                **Casey Ng** · 0:20
                Thanks for making time, Dana. Walk me through how your front desk books appointments today.

                **Dana Whitfield** · 1:05
                Mostly by phone. We lose people when the line is busy in the morning. Online booking would help, but our older patients still call, so it can't replace the phone.

                **Casey Ng** · 2:30
                If online booking sent a reminder the day before, would that change your no-show rate?

                **Dana Whitfield** · 2:52
                Reminders are the big one. Text, not email. Our patients don't read email.
            """.trimIndent(),
            summary = """
                ## Key points
                - Morning phone peaks cost the practice bookings
                - Online booking must complement the phone, not replace it
                - Text reminders matter more than email

                ## Action items
                - [ ] Send Dana the reminder prototype — **Casey Ng**, next week
            """.trimIndent(),
        ),
        Demo(
            id = "mkt-one-on-one",
            title = "1:1 with Jordan",
            source = "phone",
            minutesAgo = 4_463,
            minutes = 31,
            speakers = listOf("Alex Rivera", "Jordan Lee"),
            transcript = """
                **Alex Rivera** · 0:05
                How are you feeling about the workload with the launch coming up?

                **Jordan Lee** · 0:18
                Busy but fine. I'd like to present the checkout results at the all-hands myself; it would be good practice.
            """.trimIndent(),
            summary = """
                ## Summary
                Check-in on workload and growth goals.

                ## Action items
                - [ ] Add Jordan's checkout results to the all-hands agenda — **Alex Rivera**
            """.trimIndent(),
        ),
        Demo(
            id = "mkt-sales-call",
            title = "Call with Brightline Logistics",
            source = "note_pro",
            minutesAgo = 5_768,
            minutes = 24,
            speakers = listOf("Priya Nair", "Tom Alvarez"),
            transcript = """
                **Priya Nair** · 0:10
                You mentioned three warehouses. Would you roll out in all of them at once?

                **Tom Alvarez** · 0:34
                No, one pilot site first, Denver, for sixty days. If the numbers hold, the other two in Q2.
            """.trimIndent(),
            summary = """
                ## Summary
                Discovery call; Brightline wants a 60-day pilot in Denver before expanding.

                ## Next steps
                - [ ] Send pilot proposal and pricing — **Priya Nair**, Monday
            """.trimIndent(),
        ),
        Demo(
            id = "mkt-lecture",
            title = "Lecture: behavioral economics, week 6",
            source = "phone",
            minutesAgo = 7_235,
            minutes = 74,
            speakers = listOf("Prof. Okafor"),
            transcript = """
                **Prof. Okafor** · 0:00
                Today: loss aversion. People feel a loss about twice as strongly as a gain of the same size, and that single fact explains a lot of the choices we looked at last week.
            """.trimIndent(),
            summary = """
                ## Key concepts
                - Loss aversion: losses weigh about twice as much as equal gains
                - Framing the same choice as a gain or a loss changes decisions

                ## For the exam
                - Be able to explain the endowment effect with an example
            """.trimIndent(),
        ),
        Demo(
            id = "mkt-board-prep",
            title = "Board meeting prep",
            source = "phone",
            minutesAgo = 8_652,
            minutes = 47,
            speakers = listOf("Alex Rivera", "Priya Nair"),
            transcript = """
                **Priya Nair** · 0:15
                The board will ask about customer retention. We should open with the twelve-month numbers, not the quarterly ones.
            """.trimIndent(),
            summary = """
                ## Summary
                Agenda and story for next week's board meeting.

                ## Action items
                - [ ] Build the retention slide with twelve-month numbers — **Priya Nair**
            """.trimIndent(),
        ),
        Demo(
            id = "mkt-launch-plan",
            title = "Spring launch plan",
            source = "phone",
            minutesAgo = 10_231,
            minutes = 39,
            speakers = listOf("Sam Patel", "Maya Chen"),
            transcript = """
                **Sam Patel** · 0:30
                Two weeks before launch we tease the collection on social, one week before we email the waiting list.
            """.trimIndent(),
            summary = """
                ## Timeline
                - Two weeks out: social teasers
                - One week out: email to the waiting list
                - Launch day: checkout and collection go live together
            """.trimIndent(),
        ),
        Demo(
            id = "mkt-voice-memo",
            title = "Voice memo: gift ideas",
            source = "phone",
            minutesAgo = 11_987,
            minutes = 2,
            transcript = """
                **Me** · 0:00
                Gift ideas: a pottery class for Mom, the hiking book Sam mentioned, and check if the concert is on the 14th.
            """.trimIndent(),
            summary = """
                ## To do
                - [ ] Book the pottery class
                - [ ] Buy the hiking book
                - [ ] Check the concert date (14th?)
            """.trimIndent(),
        ),
    )
}
