package dev.velum.app

import dev.velum.app.i18n.En
import dev.velum.app.i18n.PtBr
import dev.velum.app.i18n.Strings
import java.io.File
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
        s.ux.voiceProfilesExportBlocked, s.ux.voiceProfilesChangeBlocked, s.ux.voiceProfilesEmpty,
        s.ux.voiceProfilesSaved, s.ux.voiceProfilesDeleted, s.ux.voiceProfilesExport,
        s.ux.voiceProfilesExportReady, s.ux.voiceProfilesImportTitle, s.ux.voiceProfilesImport,
        s.ux.voiceProfilesImportedFormat, s.ux.voiceProfilesWipe, s.ux.voiceProfilesWiped,
        s.ux.voiceProfilesThreshold, s.ux.voiceProfilesThresholdSet, s.ux.voiceProfilesEnrollHint,
        s.ux.voiceProfilesSamplesFormat, s.ux.voiceProfilesSensitivityFlexible,
        s.ux.voiceProfilesSensitivityBalanced, s.ux.voiceProfilesSensitivityStrict,
        s.ux.voiceProfilesSensitivityVeryStrict,
    )

    @Test
    fun bothLocalesHaveEveryVoiceProfilesString() {
        val en = copy(En)
        val pt = copy(PtBr)
        assertEquals(en.size, pt.size)
        en.zip(pt).forEachIndexed { i, (e, p) ->
            assertTrue(e.isNotBlank() && p.isNotBlank(), "blank entry #$i")
        }
        // Strings that are not the same word in both languages must differ.
        assertNotEquals(En.ux.voiceProfilesHint, PtBr.ux.voiceProfilesHint)
        assertNotEquals(En.ux.voiceProfilesEnrollHint, PtBr.ux.voiceProfilesEnrollHint)
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
        assertEquals("To add a voice, name a speaker in a recording or confirm a detected speaker.", en)
        assertEquals("Para cadastrar uma voz, nomeie um orador em uma gravação ou confirme um orador detectado.", pt)
        assertFalse(en.contains("Fontes") || en.contains("orador"))
        assertFalse(pt.contains("Sources") || pt.contains("speaker"))
    }

    @Test
    fun screenUsesStringsOnly() {
        val file = listOf("composeApp", "../composeApp", "../../composeApp")
            .map { File("$it/src/commonMain/kotlin/dev/velum/app/ui/settings/VoiceProfilesScreen.kt") }
            .first { it.isFile }
        val text = file.readText()
        assertFalse(text.contains("%.2f"), "numeric threshold labels must be localized")
        assertTrue(text.contains("voiceProfilesSensitivityFlexible"))
        assertTrue(text.contains("contentDescription"), "text fields need accessibility labels")
        // No string literal with a letter-leading English word outside imports/format tokens.
        val literals = Regex("\"([^\"\\\\]*)\"").findAll(text.lines().filterNot { it.trimStart().startsWith("import") }.joinToString("\n"))
            .map { it.groupValues[1] }
            .filter { !it.contains('$') && Regex("[A-Za-z]{3,}").containsMatchIn(it) }
            .toList()
        assertTrue(literals.isEmpty(), "hardcoded user-facing literals: $literals")
    }
}
