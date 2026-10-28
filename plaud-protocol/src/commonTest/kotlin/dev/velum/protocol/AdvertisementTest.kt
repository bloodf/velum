package dev.velum.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdvertisementTest {
    @Test
    fun parseManufacturerData_noteProProtocol20() {
        val data = AdvertisementFixtures.notePro881(portVersion = 20, serial = "881TEST1")
        val adv = PlaudAdvertisement.parseManufacturerData(data)
        assertNotNull(adv)
        assertEquals(881, adv.modelCode)
        assertEquals("Note Pro", adv.modelName)
        assertEquals("881TEST1", adv.serial)
        assertEquals(20, adv.protocolVersion)
        assertEquals(20, adv.portVersion)
        assertTrue(adv.isNoteProFamilyDiscovery)
    }

    @Test
    fun fixtures_parseExpectedPortVersionAndProject881() {
        val adv881 = PlaudAdvertisement.parseNoteProFamilyDiscovery(
            AdvertisementFixtures.notePro881(portVersion = 20),
        )
        assertNotNull(adv881)
        assertEquals(DeviceModel.NotePro, adv881.model)
        assertEquals(881, adv881.modelCode)
        assertEquals(20, adv881.portVersion)

        val adv883 = PlaudAdvertisement.parseNoteProFamilyDiscovery(
            AdvertisementFixtures.notePro883(portVersion = 21),
        )
        assertNotNull(adv883)
        assertEquals(DeviceModel.NotePro883, adv883.model)
        assertEquals(883, adv883.modelCode)
        assertEquals(21, adv883.portVersion)
    }

    @Test
    fun malformedFixtures_noCrashAndNoFalseNoteProDiscovery() {
        val malformed = listOf(
            AdvertisementFixtures.empty(),
            AdvertisementFixtures.tooShort(),
            AdvertisementFixtures.truncatedBeforePortVersion(),
            AdvertisementFixtures.allZeros(),
            AdvertisementFixtures.otherModel(),
            AdvertisementFixtures.notePro881WrongPort(portVersion = 0),
            AdvertisementFixtures.notePro881WrongPort(portVersion = 99),
            byteArrayOf(0xFF.toByte(), 0xFE.toByte()),
        )
        for (fixture in malformed) {
            val discovery = PlaudAdvertisement.parseNoteProFamilyDiscovery(fixture)
            assertNull(
                discovery,
                "malformed/unrelated fixture must not report Note Pro discovery " +
                    "(size=${fixture.size})",
            )
        }
        // Structural parse may succeed for all-zeros / wrong port, but discovery must fail.
        val zeros = PlaudAdvertisement.parseManufacturerData(AdvertisementFixtures.allZeros())
        assertNotNull(zeros)
        assertFalse(zeros.isNoteProFamilyDiscovery)
    }

    @Test
    fun toString_redactsSerialAndRawManufacturerData() {
        val serial = "881SECRET"
        val data = AdvertisementFixtures.notePro881(portVersion = 20, serial = serial)
        val adv = PlaudAdvertisement.parseManufacturerData(data)
        assertNotNull(adv)
        val text = adv.toString()
        assertEquals(
            "PlaudAdvertisement(modelCode=881, serial=[REDACTED], " +
                "protocolVersion=20, rawManufacturerData=[REDACTED])",
            text,
        )
        assertFalse(serial in text)
        assertFalse(data.contentToString() in text)
        assertFalse(adv.rawManufacturerData.contentToString() in text)
    }

    @Test
    fun crc16Ccitt_knownVector() {
        // CRC-16/CCITT-FALSE("123456789") = 0x29B1
        val crc = Crc16Ccitt.compute("123456789".encodeToByteArray())
        assertEquals(0x29B1, crc)
    }
}
