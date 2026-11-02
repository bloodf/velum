package dev.velum.recorder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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

    private fun plaudPayload(model: Int = 881): ByteArray {
        val d = ByteArray(20)
        d[1] = (model and 0xFF).toByte()
        d[2] = ((model shr 8) and 0xFF).toByte()
        "881TEST1".encodeToByteArray().copyInto(d, 9)
        d[18] = 20
        return d
    }

    @Test
    fun match_validPlaudManufacturerData_usesSharedParserOffsets() {
        val hit = assertNotNull(
            RecorderDriverRegistry.matchAdvertisement(
                name = null,
                manufacturerId = 0x005D,
                manufacturerPayload = plaudPayload(),
            ),
        )
        assertEquals("plaud", hit.driver.id)
        assertEquals(
            dev.velum.protocol.DeviceCapabilityRegistry.detect(productCode = 881, advertisedName = null).displayName,
            hit.capabilities.productLabel,
        )
    }

    @Test
    fun noMatch_invalidOrForeignManufacturerData() {
        assertNull(RecorderDriverRegistry.matchAdvertisement(name = null, manufacturerId = 0x005D, manufacturerPayload = ByteArray(20)))
        assertNull(RecorderDriverRegistry.matchAdvertisement(name = null, manufacturerId = 0x005D, manufacturerPayload = ByteArray(5)))
        assertNull(RecorderDriverRegistry.matchAdvertisement(name = null, manufacturerId = 0x005D, manufacturerPayload = null))
        assertNull(RecorderDriverRegistry.matchAdvertisement(name = null, manufacturerId = 0x004C, manufacturerPayload = plaudPayload()))
    }
}
