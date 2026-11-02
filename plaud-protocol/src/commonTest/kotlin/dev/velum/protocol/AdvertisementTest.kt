package dev.velum.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    private fun valid(model: Int = 0x0371, serial: String = "881TEST1", protocol: Int = 20): ByteArray {
        val data = ByteArray(20)
        Bytes.u16Le(model).copyInto(data, 1)
        serial.encodeToByteArray().copyInto(data, 9)
        Bytes.u16Le(protocol).copyInto(data, 18)
        return data
    }

    @Test
    fun rejects_all_zero_blob() {
        assertNull(PlaudAdvertisement.parseManufacturerData(ByteArray(20)))
        assertNull(PlaudAdvertisement.parseManufacturerData(ByteArray(31)))
    }

    @Test
    fun rejects_0xff_junk_blob() {
        assertNull(PlaudAdvertisement.parseManufacturerData(ByteArray(24) { 0xFF.toByte() }))
    }

    @Test
    fun rejects_truncated_payload() {
        for (n in 0..19) assertNull(PlaudAdvertisement.parseManufacturerData(valid().copyOf(n)), "len=$n")
    }

    @Test
    fun rejects_bad_protocol_or_model() {
        assertNull(PlaudAdvertisement.parseManufacturerData(valid(protocol = 0)), "protocol 0")
        assertNull(PlaudAdvertisement.parseManufacturerData(valid(protocol = 256)), "protocol 256")
        assertNull(PlaudAdvertisement.parseManufacturerData(valid(protocol = 0x1234)), "implausible protocol")
        assertNull(PlaudAdvertisement.parseManufacturerData(valid(model = 0)), "model 0")
    }

    @Test
    fun binary_or_0xff_serial_is_accepted_and_displayed_as_hex() {
        val ff = valid().also { for (i in 9..16) it[i] = 0xFF.toByte() }
        assertEquals("0xFFFFFFFFFFFFFFFF", PlaudAdvertisement.parseManufacturerData(ff)!!.serial)
        val ctl = valid().also { it[10] = 0x07 }
        assertEquals("0x38073154455354" + "31", PlaudAdvertisement.parseManufacturerData(ctl)!!.serial)
        val embeddedNul = valid().also { it[10] = 0 }
        assertNotNull(PlaudAdvertisement.parseManufacturerData(embeddedNul))
        // No raw control or non-ASCII char ever reaches the displayed serial.
        for (b in listOf(0x00, 0x07, 0x1B, 0x7F, 0x80, 0xC3, 0xFF)) {
            val s = PlaudAdvertisement.parseManufacturerData(valid().also { it[11] = b.toByte() })!!.serial
            assertTrue(s.all { it.code in 0x20..0x7E }, "b=$b -> $s")
        }
    }

    @Test
    fun empty_serial_is_accepted_when_structure_is_valid() {
        assertEquals("", PlaudAdvertisement.parseManufacturerData(valid(serial = ""))!!.serial)
    }

    @Test
    fun wrong_company_id_is_rejected_and_plaud_id_accepted() {
        assertNull(PlaudAdvertisement.parse(0x004C, valid())) // Apple
        assertNull(PlaudAdvertisement.parse(null, valid()))
        assertNotNull(PlaudAdvertisement.parse(PlaudUuids.MANUFACTURER_ID, valid()))
    }

    @Test
    fun structurally_valid_unknown_model_is_still_listed_as_unknown() {
        val adv = PlaudAdvertisement.parseManufacturerData(valid(model = 999, serial = "999ABCDE", protocol = 25))
        assertNotNull(adv)
        assertEquals(DeviceModel.Unknown, adv.model)
    }

    @Test
    fun known_models_and_short_trimmed_serial_still_parse() {
        for (m in listOf(860, 880, 881, 882, 883, 888)) {
            assertEquals(m, PlaudAdvertisement.parseManufacturerData(valid(model = m))!!.modelCode)
        }
        // NUL-padded shorter serial (trailing padding only) is fine.
        assertEquals("881", PlaudAdvertisement.parseManufacturerData(valid(serial = "881"))!!.serial)
    }
}
