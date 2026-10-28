package dev.velum.app.i18n

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Issue #87 / audit H13: Voice Profiles UI copy must be fully localized
 * (EN + pt-BR) with no engine jargon or mixed-language enroll hints.
 */
class VoiceProfilesLocalizationTest {

    private val bannedVoiceProfileJargon = listOf(
        "3D-Speaker",
        "3D Speaker",
        "ECAPA",
        "WeSpeaker",
        "TitaNet",
        "cosine",
        "embedding",
        "cluster",
        "sherpa",
        "Fontes", // mixed-language leftover from broken enroll hint
        "enroll",
        "JSON",
    )

    private fun voiceProfileUxFields(ux: UxStrings): List<Pair<String, String>> = listOf(
        "voiceProfilesHint" to ux.voiceProfilesHint,
        "voiceProfilesEmpty" to ux.voiceProfilesEmpty,
        "voiceProfilesSaved" to ux.voiceProfilesSaved,
        "voiceProfilesDeleted" to ux.voiceProfilesDeleted,
        "voiceProfilesExport" to ux.voiceProfilesExport,
        "voiceProfilesExportReady" to ux.voiceProfilesExportReady,
        "voiceProfilesImportTitle" to ux.voiceProfilesImportTitle,
        "voiceProfilesImport" to ux.voiceProfilesImport,
        "voiceProfilesImportedFormat" to ux.voiceProfilesImportedFormat,
        "voiceProfilesWipe" to ux.voiceProfilesWipe,
        "voiceProfilesWiped" to ux.voiceProfilesWiped,
        "voiceProfilesThreshold" to ux.voiceProfilesThreshold,
        "voiceProfilesThresholdSet" to ux.voiceProfilesThresholdSet,
        "voiceProfilesEnrollHint" to ux.voiceProfilesEnrollHint,
        "voiceProfilesSamplesFormat" to ux.voiceProfilesSamplesFormat,
        "confirmWipeProfilesBody" to ux.confirmWipeProfilesBody,
        "confirmDeleteProfileBody" to ux.confirmDeleteProfileBody,
        "deleteProfile" to ux.deleteProfile,
        "role" to ux.role,
        "company" to ux.company,
        "email" to ux.email,
    )

    @Test
    fun voiceProfileStringsExistInEnAndPtBr() {
        assertEquals("en", En.localeTag)
        assertEquals("pt-BR", PtBr.localeTag)
        assertTrue(En.voiceProfiles.isNotBlank())
        assertTrue(PtBr.voiceProfiles.isNotBlank())
        assertNotEquals(En.voiceProfiles, PtBr.voiceProfiles)

        for ((key, enValue) in voiceProfileUxFields(En.ux)) {
            assertTrue(enValue.isNotBlank(), "EN $key blank")
        }
        for ((key, ptValue) in voiceProfileUxFields(PtBr.ux)) {
            assertTrue(ptValue.isNotBlank(), "pt-BR $key blank")
        }
    }

    @Test
    fun voiceProfileCopyDiffersBetweenLocales() {
        val en = voiceProfileUxFields(En.ux).toMap()
        val pt = voiceProfileUxFields(PtBr.ux).toMap()
        val mustDiffer = listOf(
            "voiceProfilesHint",
            "voiceProfilesEmpty",
            "voiceProfilesEnrollHint",
            "voiceProfilesExport",
            "voiceProfilesImportTitle",
            "voiceProfilesWipe",
            "voiceProfilesThreshold",
            "confirmWipeProfilesBody",
            "confirmDeleteProfileBody",
        )
        for (key in mustDiffer) {
            assertNotEquals(en[key], pt[key], "$key should differ between EN and pt-BR")
        }
    }

    @Test
    fun voiceProfileCopyHasNoEngineJargon() {
        val blobs = buildList {
            add(En.voiceProfiles)
            add(PtBr.voiceProfiles)
            addAll(voiceProfileUxFields(En.ux).map { it.second })
            addAll(voiceProfileUxFields(PtBr.ux).map { it.second })
        }
        for (text in blobs) {
            for (phrase in bannedVoiceProfileJargon) {
                assertFalse(
                    text.contains(phrase, ignoreCase = true),
                    "Voice profile copy must not contain \"$phrase\": $text",
                )
            }
        }
    }

    @Test
    fun enrollHintPointsAtNameSpeakersFlow() {
        assertTrue(
            En.ux.voiceProfilesEnrollHint.contains(En.nameSpeakers),
            "EN enroll hint should mention Name speakers action",
        )
        assertTrue(
            PtBr.ux.voiceProfilesEnrollHint.contains(PtBr.nameSpeakers),
            "pt-BR enroll hint should mention Nomear oradores action",
        )
        assertTrue(
            En.ux.voiceProfilesEnrollHint.contains("recording", ignoreCase = true),
        )
        assertTrue(
            PtBr.ux.voiceProfilesEnrollHint.contains("gravação", ignoreCase = true),
        )
    }

    @Test
    fun samplesFormatAcceptsCountPlaceholder() {
        assertTrue(En.ux.voiceProfilesSamplesFormat.contains("%d"))
        assertTrue(PtBr.ux.voiceProfilesSamplesFormat.contains("%d"))
        assertTrue(En.ux.voiceProfilesImportedFormat.contains("%d"))
        assertTrue(PtBr.ux.voiceProfilesImportedFormat.contains("%d"))
        assertEquals(
            "3 sample(s)",
            En.ux.voiceProfilesSamplesFormat.replace("%d", "3"),
        )
        assertEquals(
            "3 amostra(s)",
            PtBr.ux.voiceProfilesSamplesFormat.replace("%d", "3"),
        )
    }
}
