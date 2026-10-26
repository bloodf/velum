package dev.velum.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CommandTableTest {
    @Test
    fun notePro_fw15_inferredProtocol20_usesProOpcodes() {
        val table = CommandTable.forModel(DeviceModel.NotePro, protocolVersion = 20)
        assertEquals(4, table.setClock)
        assertEquals(24, table.ledOn)
        assertEquals(107, table.setBleName)
        assertEquals(30, table.deleteFile)
        assertEquals(31, table.deleteFileRsp)
        assertTrue(table.usesEncryptedLink)
        assertTrue(table.usesRsaUnlock)
        assertFalse(table.usesEccUnlock)
    }

    @Test
    fun note_plaintext_usesNoteOpcodes() {
        val table = CommandTable.forModel(DeviceModel.Note, protocolVersion = 10)
        assertEquals(38, table.setClock)
        assertEquals(33, table.ledOn)
        assertEquals(130, table.setBleName)
        assertFalse(table.usesEncryptedLink)
    }

    @Test
    fun plaudOne_usesEcc() {
        val table = CommandTable.forModel(DeviceModel.PlaudOne, protocolVersion = 30)
        assertTrue(table.usesEccUnlock)
        assertFalse(table.usesRsaUnlock)
    }

    @Test
    fun advertisement_selectsModelAndTable() {
        val data = ByteArray(20)
        Bytes.u16Le(0x0371).copyInto(data, 1) // 881
        "881TEST1".encodeToByteArray().copyInto(data, 9)
        Bytes.u16Le(20).copyInto(data, 18)
        val adv = PlaudAdvertisement.parseManufacturerData(data)!!
        assertEquals(DeviceModel.NotePro, DeviceModel.fromCode(adv.modelCode))
        val table = CommandTable.forModel(DeviceModel.fromCode(adv.modelCode), adv.protocolVersion)
        assertEquals(20, table.protocolVersion)
    }
}
