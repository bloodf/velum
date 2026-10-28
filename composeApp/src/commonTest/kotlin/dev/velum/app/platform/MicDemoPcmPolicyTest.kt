package dev.velum.app.platform

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MicDemoPcmPolicyTest {
    @Test
    fun shouldUseDemoPcm_onlyForUnsupported() {
        assertTrue(shouldUseDemoPcm(MicStartResult.Unsupported))
        assertFalse(shouldUseDemoPcm(MicStartResult.Started))
        assertFalse(shouldUseDemoPcm(MicStartResult.PermissionRequired))
        assertFalse(shouldUseDemoPcm(MicStartResult.Failed("AudioRecord failed")))
        assertFalse(shouldUseDemoPcm(MicStartResult.Failed("")))
    }
}
