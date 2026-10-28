package dev.velum.app

import dev.velum.ai.local.DefaultLocalCatalog
import dev.velum.app.i18n.En
import dev.velum.app.i18n.ModelCatalogUi
import dev.velum.app.i18n.PtBr
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Guards user-facing copy: every catalog model has pt-BR + en blurbs,
 * no developer/engine jargon in UI strings, and no obvious hardcoded
 * English CTAs left in Compose screens.
 */
class LocalizationAndCopyTest {
    private val bannedDeveloperPhrases = listOf(
        "sherpa-onnx",
        "Needle JNI",
        ".cact",
        "WHISTLE_EVAL",
        "Pesos não vêm no APK",
        "Weights are not in the APK",
        "LiteRT-LM",
        "HF LFS",
        "OnlineRecognizer",
        "OfflineRecognizer",
        "via sherpa",
    )

    /** Engine jargon that must never appear in Voice Profiles UI strings (#87 / H13). */
    private val bannedVoiceProfilePhrases = listOf(
        "3D-Speaker",
        "ECAPA",
        "cosine",
        "cluster",
    )

    @Test
    fun everyCatalogModelHasEnAndPtBrCopy() {
        val ids = DefaultLocalCatalog.map { it.id }.toSet()
        assertEquals(ids, ModelCatalogUi.coveredIds(), "ModelCatalogUi must cover DefaultLocalCatalog")
        for (id in ids) {
            val en = ModelCatalogUi.copyFor("en", id)
            val pt = ModelCatalogUi.copyFor("pt-BR", id)
            assertTrue(en.blurb.isNotBlank(), "en blurb blank for $id")
            assertTrue(pt.blurb.isNotBlank(), "pt-BR blurb blank for $id")
            assertTrue(en.languages.isNotBlank(), "en languages blank for $id")
            assertTrue(pt.languages.isNotBlank(), "pt-BR languages blank for $id")
            assertFalse(en.blurb == pt.blurb && id.startsWith("parakeet"), "pt-BR should differ for $id")
        }
    }

    @Test
    fun stringsAndCatalogHaveNoDeveloperJargon() {
        val sources = listOf(
            resolve("composeApp/src/commonMain/kotlin/dev/velum/app/i18n/Strings.kt"),
            resolve("composeApp/src/commonMain/kotlin/dev/velum/app/i18n/ModelCatalogUi.kt"),
            resolve("ai/src/commonMain/kotlin/dev/velum/ai/local/LocalModels.kt"),
        )
        for (file in sources) {
            assertTrue(file.isFile, "missing ${file.path}")
            val text = file.readText()
            // Only scan user-facing notes/languages/string literals regions loosely.
            for (phrase in bannedDeveloperPhrases) {
                if (file.name == "LocalModels.kt") {
                    // Allow engine names only in comments / URLs / ids — not in notes = "…"
                    val notesBlocks = Regex("""notes\s*=\s*"(?:[^"\\]|\\.)*"""").findAll(text)
                        .map { it.value }
                        .joinToString("\n")
                    assertFalse(
                        notesBlocks.contains(phrase, ignoreCase = true),
                        "${file.name} notes still contain \"$phrase\"",
                    )
                } else {
                    assertFalse(
                        text.contains(phrase, ignoreCase = true),
                        "${file.name} must not contain developer phrase \"$phrase\"",
                    )
                }
            }
        }
    }

    @Test
    fun ptBrAndEnLocaleTagsAreDistinct() {
        assertEquals("pt-BR", PtBr.localeTag)
        assertEquals("en", En.localeTag)
        assertTrue(PtBr.firstRun.downloadRecommended.contains("Baixar", ignoreCase = true))
        assertTrue(En.firstRun.downloadRecommended.contains("Download", ignoreCase = true))
        assertFalse(PtBr.modelManagerHint.contains("APK", ignoreCase = true))
        assertFalse(En.modelManagerHint.contains("APK", ignoreCase = true))
    }

    @Test
    fun voiceProfilesUxHasNoEngineJargonInEitherLocale() {
        val blobs = listOf(
            En.voiceProfiles,
            PtBr.voiceProfiles,
            En.ux.voiceProfilesHint,
            PtBr.ux.voiceProfilesHint,
            En.ux.voiceProfilesEmpty,
            PtBr.ux.voiceProfilesEmpty,
            En.ux.voiceProfilesEnrollHint,
            PtBr.ux.voiceProfilesEnrollHint,
            En.ux.voiceProfilesExport,
            PtBr.ux.voiceProfilesExport,
            En.ux.voiceProfilesImportTitle,
            PtBr.ux.voiceProfilesImportTitle,
            En.ux.voiceProfilesThreshold,
            PtBr.ux.voiceProfilesThreshold,
        )
        for (text in blobs) {
            assertTrue(text.isNotBlank())
            for (phrase in bannedVoiceProfilePhrases) {
                assertFalse(
                    text.contains(phrase, ignoreCase = true),
                    "Voice Profiles copy still contains \"$phrase\": $text",
                )
            }
        }
        assertTrue(En.ux.voiceProfilesEnrollHint.contains(En.nameSpeakers))
        assertTrue(PtBr.ux.voiceProfilesEnrollHint.contains(PtBr.nameSpeakers))
        assertNotEquals(En.ux.voiceProfilesEnrollHint, PtBr.ux.voiceProfilesEnrollHint)
    }

    @Test
    fun voiceProfilesScreenUsesLocalizedStrings() {
        val screen = resolve(
            "composeApp/src/commonMain/kotlin/dev/velum/app/ui/settings/VoiceProfilesScreen.kt",
        )
        assertTrue(screen.isFile, "missing VoiceProfilesScreen.kt")
        val text = screen.readText()
            .replace(Regex("""/\*[\s\S]*?\*/"""), "")
            .replace(Regex("""(?m)^\s*//.*$"""), "")
        val bannedLiterals = listOf(
            "\"Voice profiles\"",
            "\"Encrypted on-device",
            "\"3D-Speaker",
            "\"ECAPA",
            "\"No profiles yet",
            "\"Export encrypted\"",
            "\"Import encrypted JSON\"",
            "\"Wipe all profiles\"",
            "\"Match threshold\"",
            "\"Enroll:",
            "Fontes",
            "cluster",
        )
        val offenders = bannedLiterals.filter { text.contains(it) }
        assertTrue(
            offenders.isEmpty(),
            "VoiceProfilesScreen still hardcodes: ${offenders.joinToString()}",
        )
        assertTrue(text.contains("strings.voiceProfiles"))
        assertTrue(text.contains("strings.ux.voiceProfilesHint"))
        assertTrue(text.contains("strings.ux.voiceProfilesEnrollHint"))
        assertTrue(text.contains("strings.ux.voiceProfilesExport"))
        assertTrue(text.contains("strings.ux.voiceProfilesImport"))
        assertTrue(text.contains("strings.ux.voiceProfilesWipe"))
        assertTrue(text.contains("strings.ux.confirmWipeProfilesBody"))
        assertTrue(text.contains("strings.ux.confirmDeleteProfileBody"))
    }

    @Test
    fun composeScreensAvoidHardcodedEnglishCtas() {
        val uiRoot = resolve("composeApp/src/commonMain/kotlin/dev/velum/app/ui")
        assertTrue(uiRoot.isDirectory)
        val banned = listOf(
            "\"Choose audio file\"",
            "\"Delete profile\"",
            "\"Nearby devices\"",
            "\"Local ASR size",
            "Pesos não vêm",
            "Weights are not in the APK",
        )
        val offenders = mutableListOf<String>()
        val repoRoot = uiRoot.parentFile?.parentFile?.parentFile?.parentFile
        uiRoot.walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            // Strip block/line comments so KDoc references (e.g. docs/) are ignored.
            val text = file.readText()
                .replace(Regex("""/\*[\s\S]*?\*/"""), "")
                .replace(Regex("""(?m)^\s*//.*$"""), "")
            for (phrase in banned) {
                if (text.contains(phrase)) {
                    val label = if (repoRoot != null) file.relativeTo(repoRoot).path else file.path
                    offenders += "$label:$phrase"
                }
            }
        }
        assertTrue(offenders.isEmpty(), "Hardcoded user CTAs:\n${offenders.joinToString("\n")}")
    }

    private fun resolve(rel: String): File {
        val candidates = listOf(File(rel), File("../$rel"), File("../../$rel"))
        return candidates.firstOrNull { it.isFile || it.isDirectory }
            ?: File(rel)
    }
}
