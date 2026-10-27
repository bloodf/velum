package dev.velum.recorder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RecorderDriverRegistryTest {

    @Test
    fun registry_containsPlaudOmiPocket() {
        assertEquals(
            listOf("plaud", "omi", "pocket"),
            RecorderDriverRegistry.drivers.map { it.id },
        )
    }

    @Test
    fun match_omiServiceUuid() {
        val hit = assertNotNull(
            RecorderDriverRegistry.matchAdvertisement(
                name = "omi-devkit",
                serviceUuids = listOf("19B10000-E8F2-537E-4F6C-D104768A1214"),
            ),
        )
        assertEquals("omi", hit.driver.id)
        assertTrue(hit.capabilities.supportsLiveAudioStream)
        assertTrue(!hit.capabilities.supportsFileListDownload)
    }

    @Test
    fun match_pocketNamePrefix() {
        val hit = assertNotNull(
            RecorderDriverRegistry.matchAdvertisement(name = "PKT01_GREY_ABCDEF"),
        )
        assertEquals("pocket", hit.driver.id)
        assertTrue(hit.capabilities.supportsFileListDownload)
        assertTrue(hit.capabilities.audioCodecs.contains(RecorderAudioCodec.Mp3))
    }

    @Test
    fun match_plaudName() {
        val hit = assertNotNull(
            RecorderDriverRegistry.matchAdvertisement(name = "NotePin-ABC"),
        )
        assertEquals("plaud", hit.driver.id)
        assertTrue(hit.capabilities.needsImportedIdentityKeys)
    }
}
