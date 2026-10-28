package dev.velum.core.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class SummaryVersioningTest {
    @Test
    fun save_new_summary_creates_version_and_updates_recording() = runTest {
        val repo = VelumRepository(createDatabase(DriverFactory()))
        repo.insertRecording(
            id = "sv-rec-1",
            sessionId = null,
            folderId = null,
            title = "Meeting",
            source = "import",
            deviceSerial = null,
            startedAt = 1,
            durationSeconds = 10,
            sizeBytes = 100,
            localPath = null,
            transcript = "hello",
            summaryMarkdown = null,
            status = "ready",
        )

        val versionId = repo.saveSummaryVersion(
            recordingId = "sv-rec-1",
            markdown = "## First\n- a",
            templateId = "velum.meeting",
            templateName = "Meeting",
            versionId = "sv-v1",
            createdAt = 100,
        )

        assertEquals("sv-v1", versionId)
        val recording = repo.getRecording("sv-rec-1")
        assertEquals("## First\n- a", recording?.summary_markdown)
        val versions = repo.getSummaryVersions("sv-rec-1")
        assertEquals(1, versions.size)
        assertEquals("sv-v1", versions.single().id)
        assertEquals(1L, versions.single().is_active)
        assertEquals("Meeting", versions.single().template_name)
        assertEquals("velum.meeting", versions.single().template_id)
    }

    @Test
    fun regenerate_snapshots_previous_and_activates_new() = runTest {
        val repo = VelumRepository(createDatabase(DriverFactory()))
        repo.insertRecording(
            id = "sv-rec-2",
            sessionId = null,
            folderId = null,
            title = "Call",
            source = "import",
            deviceSerial = null,
            startedAt = 1,
            durationSeconds = 10,
            sizeBytes = 100,
            localPath = null,
            transcript = "hi",
            summaryMarkdown = "## Old notes\n- prior",
            status = "ready",
        )

        repo.saveSummaryVersion(
            recordingId = "sv-rec-2",
            markdown = "## New notes\n- next",
            templateId = "velum.general",
            templateName = "General",
            versionId = "sv-v2",
            createdAt = 200,
        )

        val versions = repo.getSummaryVersions("sv-rec-2")
        assertEquals(2, versions.size)
        val active = versions.first { it.is_active != 0L }
        val inactive = versions.first { it.is_active == 0L }
        assertEquals("sv-v2", active.id)
        assertEquals("## New notes\n- next", active.markdown)
        assertEquals("## Old notes\n- prior", inactive.markdown)
        assertEquals("## New notes\n- next", repo.getRecording("sv-rec-2")?.summary_markdown)
        assertEquals(1, versions.count { it.is_active != 0L })
    }

    @Test
    fun activate_switches_recording_summary_markdown() = runTest {
        val repo = VelumRepository(createDatabase(DriverFactory()))
        repo.insertRecording(
            id = "sv-rec-3",
            sessionId = null,
            folderId = null,
            title = "Switch",
            source = "import",
            deviceSerial = null,
            startedAt = 1,
            durationSeconds = 10,
            sizeBytes = 100,
            localPath = null,
            transcript = "t",
            summaryMarkdown = null,
            status = "ready",
        )
        repo.saveSummaryVersion(
            recordingId = "sv-rec-3",
            markdown = "v1 body",
            templateId = "velum.meeting",
            templateName = "Meeting",
            versionId = "sv-a",
            createdAt = 10,
        )
        // Second save without baseline (already versioned) — deactivate first.
        repo.saveSummaryVersion(
            recordingId = "sv-rec-3",
            markdown = "v2 body",
            templateId = "velum.it",
            templateName = "IT",
            versionId = "sv-b",
            createdAt = 20,
        )

        assertEquals("v2 body", repo.getRecording("sv-rec-3")?.summary_markdown)
        repo.activateSummaryVersion("sv-rec-3", "sv-a")
        assertEquals("v1 body", repo.getRecording("sv-rec-3")?.summary_markdown)
        val versions = repo.getSummaryVersions("sv-rec-3")
        assertTrue(versions.first { it.id == "sv-a" }.is_active != 0L)
        assertTrue(versions.first { it.id == "sv-b" }.is_active == 0L)
    }

    @Test
    fun delete_non_active_ok_and_delete_active_falls_back() = runTest {
        val repo = VelumRepository(createDatabase(DriverFactory()))
        repo.insertRecording(
            id = "sv-rec-4",
            sessionId = null,
            folderId = null,
            title = "Delete",
            source = "import",
            deviceSerial = null,
            startedAt = 1,
            durationSeconds = 10,
            sizeBytes = 100,
            localPath = null,
            transcript = "t",
            summaryMarkdown = null,
            status = "ready",
        )
        repo.saveSummaryVersion(
            recordingId = "sv-rec-4",
            markdown = "older",
            templateId = "velum.meeting",
            templateName = "Meeting",
            versionId = "sv-old",
            createdAt = 10,
        )
        repo.saveSummaryVersion(
            recordingId = "sv-rec-4",
            markdown = "newer",
            templateId = "velum.general",
            templateName = "General",
            versionId = "sv-new",
            createdAt = 20,
        )

        repo.deleteSummaryVersion("sv-rec-4", "sv-old")
        var versions = repo.getSummaryVersions("sv-rec-4")
        assertEquals(1, versions.size)
        assertEquals("sv-new", versions.single().id)
        assertEquals("newer", repo.getRecording("sv-rec-4")?.summary_markdown)

        repo.deleteSummaryVersion("sv-rec-4", "sv-new")
        versions = repo.getSummaryVersions("sv-rec-4")
        assertTrue(versions.isEmpty())
        assertNull(repo.getRecording("sv-rec-4")?.summary_markdown)
    }

    @Test
    fun delete_active_activates_most_recent_remaining() = runTest {
        val repo = VelumRepository(createDatabase(DriverFactory()))
        repo.insertRecording(
            id = "sv-rec-5",
            sessionId = null,
            folderId = null,
            title = "Fallback",
            source = "import",
            deviceSerial = null,
            startedAt = 1,
            durationSeconds = 10,
            sizeBytes = 100,
            localPath = null,
            transcript = "t",
            summaryMarkdown = null,
            status = "ready",
        )
        repo.saveSummaryVersion(
            recordingId = "sv-rec-5",
            markdown = "a",
            templateId = null,
            templateName = "A",
            versionId = "sv-1",
            createdAt = 10,
        )
        repo.saveSummaryVersion(
            recordingId = "sv-rec-5",
            markdown = "b",
            templateId = null,
            templateName = "B",
            versionId = "sv-2",
            createdAt = 20,
        )
        repo.saveSummaryVersion(
            recordingId = "sv-rec-5",
            markdown = "c",
            templateId = null,
            templateName = "C",
            versionId = "sv-3",
            createdAt = 30,
        )

        repo.deleteSummaryVersion("sv-rec-5", "sv-3")
        val versions = repo.getSummaryVersions("sv-rec-5")
        assertEquals(2, versions.size)
        val active = versions.first { it.is_active != 0L }
        assertEquals("sv-2", active.id)
        assertEquals("b", repo.getRecording("sv-rec-5")?.summary_markdown)
        assertFalse(versions.any { it.id == "sv-3" })
        assertNotNull(versions.firstOrNull { it.id == "sv-1" })
    }
}
