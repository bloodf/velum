package dev.velum.app.ui.files

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.velum.app.i18n.En
import dev.velum.app.i18n.PtBr
import dev.velum.app.ui.recording.transcriptBodyLineDisplay
import dev.velum.app.ui.recording.transcriptHeaderDisplay
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class, sdk = [34], qualifiers = "pt-rBR")
class ImportedRecordingDisplayTest {
    @Test
    fun shortFilesShowSecondsNotZeroMinutes() {
        assertEquals("36 s", LibraryListLogic.durationLabel(36, En))
        assertEquals("6 s", LibraryListLogic.durationLabel(6, PtBr))
        assertEquals("1 min", LibraryListLogic.durationLabel(60, En))
        assertEquals("2 min", LibraryListLogic.durationLabel(179, PtBr))
        assertEquals("0 s", LibraryListLogic.durationLabel(-5, En))
    }

    @Test
    fun importsAreNotLabelledAsHeadphones() {
        for (s in listOf(En, PtBr)) {
            assertEquals(s.ux.importedAudioA11y, LibraryListLogic.sourceA11y("import", s))
            assertEquals(s.ux.importedAudioA11y, LibraryListLogic.sourceA11y("share", s))
            assertEquals(s.ux.noteProMicA11y, LibraryListLogic.sourceA11y("note_pro", s))
            assertNotEquals(s.ux.headphonesA11y, s.ux.importedAudioA11y)
        }
    }

    @Test
    fun transcriptHeaderHasNoMarkdownMarkersAndLocalizesGeneratedSpeakers() {
        assertEquals("Speaker 1 · 0:00", transcriptHeaderDisplay("**Speaker 1** · 0:00", En))
        assertEquals("Orador 1 · 0:00", transcriptHeaderDisplay("**Speaker 1** · 0:00", PtBr))
        assertEquals("Ana · 0:00", transcriptHeaderDisplay("**Ana** · 0:00", PtBr))
        assertEquals("Speaker Phil · 1:02", transcriptHeaderDisplay("**Speaker Phil** · 1:02", PtBr))
    }

    @Test
    fun markersSectionShowsNoMarkdownInEitherLocale() {
        assertEquals("Markers", transcriptBodyLineDisplay("## Markers", En))
        assertEquals("Marcadores", transcriptBodyLineDisplay("## Markers", PtBr))
        assertEquals("• 0:12 budget", transcriptBodyLineDisplay("- **0:12** budget", En))
        assertEquals("• 1:05", transcriptBodyLineDisplay("- **1:05**", PtBr))
        assertEquals("plain **bold** line".replace("**", ""), transcriptBodyLineDisplay("plain **bold** line", En))
    }

    @Test
    fun displayNamesKeepTheirWordsThroughTheCacheFileName() {
        val cached = "123456789012_" + dev.velum.app.platform.sanitizeImportFileName("Team standup (v2).wav")
        assertEquals("Team standup v2", dev.velum.app.importing.ImportTitles.fromFileName(cached))
    }

    @Test
    fun portugueseSheetScrimIsNotCalledASpreadsheet() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val id = context.resources.getIdentifier("close_sheet", "string", context.packageName)
        assertNotEquals(0, id, "close_sheet not merged into the app resources")
        assertEquals("Fechar painel", context.getString(id))
    }
}
