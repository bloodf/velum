package dev.velum.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AdvertisementTest {
    @Test
    fun parseManufacturerData_noteProProtocol20() {
        // Synthetic layout matching documented offsets.
        val data = ByteArray(20)
        // bytes 1-2 model 0x0371 = 881
        Bytes.u16Le(0x0371).copyInto(data, 1)
        "881TEST1".encodeToByteArray().copyInto(data, 9)
        // protocol 20 = 0x0014
        Bytes.u16Le(20).copyInto(data, 18)

        val adv = PlaudAdvertisement.parseManufacturerData(data)
        assertNotNull(adv)
        assertEquals(881, adv.modelCode)
        assertEquals("Note Pro", adv.modelName)
        assertEquals("881TEST1", adv.serial)
        assertEquals(20, adv.protocolVersion)
    }

    @Test
    fun crc16Ccitt_knownVector() {
        // CRC-16/CCITT-FALSE("123456789") = 0x29B1
        val crc = Crc16Ccitt.compute("123456789".encodeToByteArray())
        assertEquals(0x29B1, crc)
    }
}
