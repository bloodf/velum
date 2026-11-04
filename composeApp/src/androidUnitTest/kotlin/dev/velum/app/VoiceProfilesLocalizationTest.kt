package dev.velum.app

import dev.velum.app.i18n.En
import dev.velum.app.i18n.PtBr
import dev.velum.app.i18n.Strings
import dev.velum.app.ui.settings.importedStatus
import dev.velum.app.ui.settings.sampleCountLabel
import dev.velum.app.ui.settings.thresholdOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class VoiceProfilesLocalizationTest {
    private fun copy(s: Strings) = listOf(
        s.voiceProfiles,
        s.ux.voiceProfilesHint, s.ux.voiceProfilesUnreadableTitle, s.ux.voiceProfilesMissingMetadata,
        s.ux.voiceProfilesMissingEmbedding, s.ux.voiceProfilesInvalidMetadata, s.ux.voiceProfilesInvalidEmbedding,
        s.ux.voiceProfilesCannotDecrypt, s.ux.voiceProfilesFileUnavailable, s.ux.voiceProfilesKeyUnavailable,
        s.ux.voiceProfilesRemoveFiles, s.ux.voiceProfilesRemoveFilesConfirm, s.ux.voiceProfilesFilesChanged,
        s.ux.voiceProfilesExportBlocked, s.ux.voiceProfilesChangeBlocked, s.ux.voiceProfilesEmpty, s.ux.voiceProfilesNoCachedVoice,
        s.ux.voiceProfilesSaved, s.ux.voiceProfilesDeleted, s.ux.voiceProfilesExport,
        s.ux.voiceProfilesExportReady, s.ux.voiceProfilesExportEmpty, s.ux.voiceProfilesImportInvalid, s.ux.voiceProfilesImportWrongKey, s.ux.voiceProfilesImportTitle, s.ux.voiceProfilesImport,
        s.ux.voiceProfilesImportedFormat, s.ux.voiceProfilesWipe, s.ux.voiceProfilesWiped,
        s.ux.voiceProfilesThreshold, s.ux.voiceProfilesThresholdSet, s.ux.voiceProfilesEnrollHint,
        s.ux.voiceProfilesSamplesFormat, s.ux.voiceProfilesSampleOne, s.ux.voiceProfilesImportedOne, s.ux.voiceProfilesSensitivityFlexible,
        s.ux.voiceProfilesSensitivityBalanced, s.ux.voiceProfilesSensitivityStrict,
        s.ux.voiceProfilesSensitivityVeryStrict,
    )

    @Test
    fun countsUseSingularAndPlural() {
        assertEquals("1 sample", sampleCountLabel(En, 1))
        assertEquals("2 samples", sampleCountLabel(En, 2))
        assertEquals("0 samples", sampleCountLabel(En, 0))
        assertEquals("1 amostra", sampleCountLabel(PtBr, 1))
        assertEquals("3 amostras", sampleCountLabel(PtBr, 3))
        assertEquals("Imported 1 profile", importedStatus(En, 1))
        assertEquals("Imported 2 profiles", importedStatus(En, 2))
        assertEquals("1 perfil importado", importedStatus(PtBr, 1))
        assertEquals("2 perfis importados", importedStatus(PtBr, 2))
    }

    @Test
    fun bothLocalesHaveDistinctNonBlankCopy() {
        copy(En).zip(copy(PtBr)).forEachIndexed { i, (e, p) ->
            assertTrue(e.isNotBlank() && p.isNotBlank(), "blank entry #$i")
            assertNotEquals(e, p, "entry #$i is identical in en and pt-BR: $e")
        }
    }

    @Test
    fun noEngineJargonInEitherLocale() {
        val banned = listOf("3D-Speaker", "ECAPA", "embedding", "cluster", "JSON", "limiar", "threshold", "similarity")
        for ((name, all) in mapOf("en" to copy(En), "pt-BR" to copy(PtBr))) {
            for (text in all) for (b in banned) {
                assertFalse(text.contains(b, ignoreCase = true), "$name copy contains \"$b\": $text")
            }
        }
    }

    @Test
    fun enrollHintIsSingleLanguage() {
        val en = En.ux.voiceProfilesEnrollHint
        val pt = PtBr.ux.voiceProfilesEnrollHint
        assertFalse(en.contains("Fontes") || en.contains("orador"), en)
        assertFalse(pt.contains("Sources") || pt.contains("speaker"), pt)
    }

    @Test
    fun thresholdOptionsMapValuesToLabelsInOrder() {
        for (s in listOf(En, PtBr)) {
            assertEquals(
                listOf(
                    0.45f to s.ux.voiceProfilesSensitivityFlexible,
                    0.55f to s.ux.voiceProfilesSensitivityBalanced,
                    0.65f to s.ux.voiceProfilesSensitivityStrict,
                    0.75f to s.ux.voiceProfilesSensitivityVeryStrict,
                ),
                thresholdOptions(s),
            )
        }
    }
}
