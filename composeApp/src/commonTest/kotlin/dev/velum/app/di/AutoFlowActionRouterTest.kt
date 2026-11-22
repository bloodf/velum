package dev.velum.app.di

import dev.velum.ai.integrations.DestinationResult
import dev.velum.ai.integrations.WebhookFailure
import dev.velum.ai.pipeline.AutoFlowUiAction
import dev.velum.app.platform.ExportFolderResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class AutoFlowActionRouterTest {
    /** The runner's flow: no replay, like AutoFlowRunner.actions; anything emitted with no collector is dropped. */
    private fun runnerActions() = MutableSharedFlow<AutoFlowUiAction>(extraBufferCapacity = 8)

    @Test fun webhook_and_folder_export_run_with_no_screen_collecting() = runTest {
        val posted = mutableListOf<String>()
        val exported = mutableListOf<String>()
        val router = AutoFlowActionRouter(
            postWebhook = { posted += it; "HTTP 204" },
            exportToFolder = { exported += it; ExportFolderResult.Ok("Vault/note.md") },
        )
        val actions = runnerActions()
        val collector = router.start(backgroundScope, actions)
        // No screen: nothing collects uiEvents. The runner emits right after start().
        actions.emit(AutoFlowUiAction.PostWebhook("rec-1"))
        actions.emit(AutoFlowUiAction.ExportMarkdownToFolder("rec-2"))
        runCurrent()
        assertEquals(listOf("rec-1"), posted)
        assertEquals(listOf("rec-2"), exported)
        // A screen that appears later still gets the outcomes, once.
        val shown = router.uiEvents.take(2).toList()
        assertEquals(AutoFlowUiEvent.WebhookPosted("HTTP 204"), shown[0])
        assertEquals(AutoFlowUiEvent.FolderExported(ExportFolderResult.Ok("Vault/note.md")), shown[1])
        collector.cancel()
    }

    @Test fun start_subscribes_before_returning_so_an_immediate_emit_is_not_dropped() = runTest {
        var posted = 0
        val router = AutoFlowActionRouter(postWebhook = { posted++; "HTTP 200" }, exportToFolder = { null })
        val actions = runnerActions()
        router.start(backgroundScope, actions)
        assertEquals(1, actions.subscriptionCount.value)
        assertTrue(actions.tryEmit(AutoFlowUiAction.PostWebhook("rec")))
        runCurrent()
        assertEquals(1, posted)
    }

    @Test fun screen_only_actions_wait_for_a_screen_and_are_delivered_once() = runTest {
        val router = AutoFlowActionRouter(postWebhook = { error("unused") }, exportToFolder = { error("unused") })
        val actions = runnerActions()
        router.start(backgroundScope, actions)
        actions.emit(AutoFlowUiAction.ShareMarkdown("rec-share"))
        actions.emit(AutoFlowUiAction.ProcessingFailed)
        runCurrent()
        assertEquals(AutoFlowUiEvent.Launch(AutoFlowUiAction.ShareMarkdown("rec-share")), router.uiEvents.first())
        assertEquals(AutoFlowUiEvent.Launch(AutoFlowUiAction.ProcessingFailed), router.uiEvents.first())
        // Nothing is replayed to the next screen.
        val next = async { router.uiEvents.first() }
        runCurrent()
        assertTrue(next.isActive)
        next.cancel()
    }

    @Test fun a_throwing_post_action_reports_failure_and_keeps_collecting() = runTest {
        val router = AutoFlowActionRouter(
            postWebhook = { if (it == "bad") error("boom") else "HTTP 200" },
            exportToFolder = { throw IllegalStateException("no folder") },
        )
        val actions = runnerActions()
        router.start(backgroundScope, actions)
        actions.emit(AutoFlowUiAction.PostWebhook("bad"))
        actions.emit(AutoFlowUiAction.ExportMarkdownToFolder("rec"))
        actions.emit(AutoFlowUiAction.PostWebhook("good"))
        runCurrent()
        val events = router.uiEvents.take(3).toList()
        assertEquals(AutoFlowUiEvent.WebhookPosted(WebhookFailure.CONNECTION), events[0])
        assertIs<ExportFolderResult.Failed>(assertIs<AutoFlowUiEvent.FolderExported>(events[1]).result)
        assertEquals(AutoFlowUiEvent.WebhookPosted("HTTP 200"), events[2])
    }

    @Test fun cancellation_of_a_post_action_is_not_reported_as_a_failure() = runTest {
        val router = AutoFlowActionRouter(
            postWebhook = { throw CancellationException("stopped") },
            exportToFolder = { null },
        )
        var cancelled = false
        try {
            router.handle(AutoFlowUiAction.PostWebhook("rec"))
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
    }

    @Test fun with_no_screen_for_a_long_time_the_oldest_events_are_dropped_not_the_newest() = runTest {
        val router = AutoFlowActionRouter(postWebhook = { "HTTP 200" }, exportToFolder = { null }, capacity = 2)
        repeat(3) { router.handle(AutoFlowUiAction.ShareMarkdown("rec-$it")) }
        assertEquals(
            listOf("rec-1", "rec-2"),
            router.uiEvents.take(2).toList().map { ((it as AutoFlowUiEvent.Launch).action as AutoFlowUiAction.ShareMarkdown).recordingId },
        )
    }

    @Test fun a_slow_webhook_does_not_hold_back_the_next_action() = runTest {
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val router = AutoFlowActionRouter(
            postWebhook = { release.await(); "HTTP 200" },
            exportToFolder = { ExportFolderResult.Ok("Vault/b.md") },
        )
        val actions = runnerActions()
        router.start(backgroundScope, actions)
        actions.emit(AutoFlowUiAction.PostWebhook("slow"))
        actions.emit(AutoFlowUiAction.ExportMarkdownToFolder("b"))
        actions.emit(AutoFlowUiAction.ShareMarkdown("c"))
        runCurrent()
        // Both arrive while the webhook is still waiting (their order between themselves does not matter).
        assertEquals(
            setOf(
                AutoFlowUiEvent.FolderExported(ExportFolderResult.Ok("Vault/b.md")),
                AutoFlowUiEvent.Launch(AutoFlowUiAction.ShareMarkdown("c")),
            ),
            router.uiEvents.take(2).toList().toSet(),
        )
        release.complete(Unit)
        runCurrent()
        assertEquals(AutoFlowUiEvent.WebhookPosted("HTTP 200"), router.uiEvents.first())
    }

    @Test fun slack_and_notion_run_headless_and_report_their_outcome_once() = runTest {
        val slack = mutableListOf<String>()
        val notion = mutableListOf<String>()
        val router = AutoFlowActionRouter(
            postWebhook = { error("unused") },
            exportToFolder = { error("unused") },
            postSlack = { slack += it; DestinationResult.Sent() },
            exportNotion = { notion += it; if (it == "bad") error("boom") else DestinationResult.Sent("https://notion.so/p") },
        )
        val actions = runnerActions()
        router.start(backgroundScope, actions)
        actions.emit(AutoFlowUiAction.PostSlack("s1"))
        actions.emit(AutoFlowUiAction.ExportNotion("bad"))
        actions.emit(AutoFlowUiAction.ExportNotion("n1"))
        runCurrent()
        assertEquals(listOf("s1"), slack)
        assertEquals(listOf("bad", "n1"), notion)
        assertEquals(
            setOf(
                AutoFlowUiEvent.SlackPosted(DestinationResult.Sent()),
                AutoFlowUiEvent.NotionExported(DestinationResult.Failed(WebhookFailure.CONNECTION)),
                AutoFlowUiEvent.NotionExported(DestinationResult.Sent("https://notion.so/p")),
            ),
            router.uiEvents.take(3).toList().toSet(),
        )
    }

    @Test fun slack_and_notion_default_to_not_configured_when_not_wired() = runTest {
        val router = AutoFlowActionRouter(postWebhook = { null }, exportToFolder = { null })
        router.handle(AutoFlowUiAction.PostSlack("r"))
        router.handle(AutoFlowUiAction.ExportNotion("r"))
        assertEquals(
            listOf<AutoFlowUiEvent>(
                AutoFlowUiEvent.SlackPosted(DestinationResult.NotConfigured),
                AutoFlowUiEvent.NotionExported(DestinationResult.NotConfigured),
            ),
            router.uiEvents.take(2).toList(),
        )
    }
}
