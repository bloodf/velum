package dev.velum.ai.local

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalAsrRouterTierTest {

    @Test
    fun whistleLanguages_matchPublishedSet() {
        assertEquals(
            setOf("en", "de", "fr", "es", "it", "nl", "pl"),
            LocalAsrRouter.whistleLanguages,
        )
    }

    @Test
    fun importantLanguages_includePortugueseAndWhistleSet() {
        assertTrue(ImportantLanguages.codes.containsAll(listOf("pt", "en", "es", "fr", "de", "it", "nl", "pl")))
        assertTrue(ImportantLanguages.codes.containsAll(listOf("ja", "zh", "ko", "ru", "ar", "hi", "tr")))
        // Portuguese is important but not a Whistle language.
        assertFalse(LocalAsrRouter.supportsWhistle("pt-BR"))
        assertTrue("pt" in ImportantLanguages.codes)
    }

    @Test
    fun tinyTier_whistleForSupportedLanguage() {
        assertEquals(
            LocalAsrRouter.WHISTLE_ID,
            LocalAsrRouter.chooseModelId("en", tier = AsrSizeTier.Tiny, deviceClass = DeviceComputeClass.High),
        )
        assertEquals(
            LocalAsrRouter.WHISTLE_ID,
            LocalAsrRouter.chooseModelId("de-DE", tier = AsrSizeTier.Tiny, deviceClass = DeviceComputeClass.Mid),
        )
    }

    @Test
    fun tinyTier_whisperTinyForPortugueseAndOtherMajors() {
        assertEquals(
            LocalAsrRouter.TINY_ID,
            LocalAsrRouter.chooseModelId("pt-BR", tier = AsrSizeTier.Tiny, deviceClass = DeviceComputeClass.High),
        )
        assertEquals(
            LocalAsrRouter.TINY_ID,
            LocalAsrRouter.chooseModelId("ja", tier = AsrSizeTier.Tiny, deviceClass = DeviceComputeClass.Low),
        )
        assertEquals(
            LocalAsrRouter.TINY_ID,
            LocalAsrRouter.chooseModelId("ar", tier = AsrSizeTier.Tiny, deviceClass = DeviceComputeClass.Low),
        )
    }

    @Test
    fun autoLowRam_routesByLanguage() {
        assertEquals(
            LocalAsrRouter.WHISTLE_ID,
            LocalAsrRouter.chooseModelId("es", tier = AsrSizeTier.Auto, deviceClass = DeviceComputeClass.Low),
        )
        assertEquals(
            LocalAsrRouter.TINY_ID,
            LocalAsrRouter.chooseModelId("pt", tier = AsrSizeTier.Auto, deviceClass = DeviceComputeClass.Low),
        )
        assertEquals(
            LocalAsrRouter.TINY_ID,
            LocalAsrRouter.chooseModelId("zh", tier = AsrSizeTier.Auto, deviceClass = DeviceComputeClass.Low),
        )
        // Unknown / auto language on low-end → Whisper Tiny (broader coverage).
        assertEquals(
            LocalAsrRouter.TINY_ID,
            LocalAsrRouter.chooseModelId("auto", tier = AsrSizeTier.Auto, deviceClass = DeviceComputeClass.Low),
        )
        assertEquals(
            LocalAsrRouter.TINY_ID,
            LocalAsrRouter.chooseModelId(null, tier = AsrSizeTier.Auto, deviceClass = DeviceComputeClass.Low),
        )
    }

    @Test
    fun autoMid_parakeetForPortuguese() {
        assertEquals(
            LocalAsrRouter.STANDARD_ID,
            LocalAsrRouter.chooseModelId("pt-BR", tier = AsrSizeTier.Auto, deviceClass = DeviceComputeClass.Mid),
        )
    }

    @Test
    fun qualityTier_whisperTurboForNonEu() {
        assertEquals(
            LocalAsrRouter.QUALITY_ID,
            LocalAsrRouter.chooseModelId("ar", tier = AsrSizeTier.Quality, deviceClass = DeviceComputeClass.Mid),
        )
    }

    @Test
    fun cjk_usesSenseVoiceOnStandard() {
        assertEquals(
            LocalAsrRouter.CJK_LIGHT_ID,
            LocalAsrRouter.chooseModelId("ja", tier = AsrSizeTier.Standard, deviceClass = DeviceComputeClass.High),
        )
    }

    @Test
    fun chooseLowEndModelId_helpers() {
        assertEquals(LocalAsrRouter.WHISTLE_ID, LocalAsrRouter.chooseLowEndModelId("pl"))
        assertEquals(LocalAsrRouter.TINY_ID, LocalAsrRouter.chooseLowEndModelId("hi"))
    }
}
