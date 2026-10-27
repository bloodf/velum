package dev.velum.ai.local

import kotlin.test.Test
import kotlin.test.assertEquals

class LocalAsrRouterTierTest {

    @Test
    fun tinyTier_alwaysWhisperTiny() {
        assertEquals(
            LocalAsrRouter.TINY_ID,
            LocalAsrRouter.chooseModelId("pt-BR", tier = AsrSizeTier.Tiny, deviceClass = DeviceComputeClass.High),
        )
    }

    @Test
    fun autoLowRam_picksTinyEvenForPortuguese() {
        assertEquals(
            LocalAsrRouter.TINY_ID,
            LocalAsrRouter.chooseModelId("pt", tier = AsrSizeTier.Auto, deviceClass = DeviceComputeClass.Low),
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
}
