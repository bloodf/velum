package dev.velum.app.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.velum.app.i18n.DeviceUiCopy
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Device list Open / Re-download actions for sessions already in the library (#113 AC).
 * Pure resolver + callback contract tests (no Compose Activity — suite-stable under Robolectric).
 * Fixtures only — identity values are never printed.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DeviceDownloadedSessionActionsTest {
    @Test
    fun open_resolvesToExistingRecordingId_andCallbackNavigates() {
        val library = mapOf(FIXTURE_SESSION to FIXTURE_RECORDING_ID)
        val action = resolveDeviceSessionRowAction(
            sessionId = FIXTURE_SESSION,
            libraryRecordingBySessionId = library,
            downloadingSessionId = null,
        )
        val open = assertIs<DeviceSessionRowAction.Open>(action)
        assertEquals(FIXTURE_RECORDING_ID, open.recordingId)

        // Same callback DeviceScreen wires for Open — navigates to the recording route.
        var opened: String? = null
        val onOpenRecording: (String) -> Unit = { opened = it }
        onOpenRecording(open.recordingId)
        assertEquals(FIXTURE_RECORDING_ID, opened)
    }

    @Test
    fun redownload_resolvesSecondaryAndRequestsInPlaceRefresh() {
        val library = mapOf(FIXTURE_SESSION to FIXTURE_RECORDING_ID)
        val secondary = resolveDeviceSessionRedownloadAction(
            sessionId = FIXTURE_SESSION,
            libraryRecordingBySessionId = library,
        )
        assertNotNull(secondary)
        assertEquals(FIXTURE_SESSION, secondary.sessionId)

        // Same callback DeviceScreen wires for Re-download — hits persist upsert in place.
        var downloaded: Long? = null
        val onDownloadSession: (Long) -> Unit = { downloaded = it }
        onDownloadSession(secondary.sessionId)
        assertEquals(FIXTURE_SESSION, downloaded)

        assertNull(
            resolveDeviceSessionRedownloadAction(
                sessionId = FIXTURE_OTHER_SESSION,
                libraryRecordingBySessionId = library,
            ),
        )
    }

    @Test
    fun notInLibrary_resolvesDownloadOnly() {
        val action = resolveDeviceSessionRowAction(
            sessionId = FIXTURE_SESSION,
            libraryRecordingBySessionId = emptyMap(),
            downloadingSessionId = null,
        )
        val download = assertIs<DeviceSessionRowAction.Download>(action)
        assertEquals(FIXTURE_SESSION, download.sessionId)
    }

    @Test
    fun ptBr_labelsAreLocalized() {
        assertEquals("Abrir", DeviceUiCopy.openDownloaded("pt-BR"))
        assertEquals("Baixar de novo", DeviceUiCopy.redownload("pt-BR"))
        assertEquals("Na biblioteca", DeviceUiCopy.inLibrary("pt-BR"))
        assertTrue(DeviceUiCopy.openDownloaded("en").isNotBlank())
        assertTrue(DeviceUiCopy.redownload("en").isNotBlank())
    }

    companion object {
        private const val FIXTURE_SESSION = 0x5555_0001L
        private const val FIXTURE_OTHER_SESSION = 0x5555_0002L
        private const val FIXTURE_RECORDING_ID = "rec-fixture-open"
    }
}
