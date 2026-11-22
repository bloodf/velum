package dev.velum.ai.local

import kotlin.test.Test
import kotlin.test.assertEquals

class LocalAsrRouterIosTest {
    @Test fun tiny_and_low_memory_defaults_use_a_supported_ios_runtime() {
        assertEquals("whisper-tiny", LocalAsrRouter.chooseModelId(
            "en", tier = AsrSizeTier.Tiny, deviceClass = DeviceComputeClass.High,
        ))
        assertEquals("whisper-tiny", LocalAsrRouter.chooseModelId(
            "de-DE", tier = AsrSizeTier.Auto, deviceClass = DeviceComputeClass.Low,
        ))
    }
}
