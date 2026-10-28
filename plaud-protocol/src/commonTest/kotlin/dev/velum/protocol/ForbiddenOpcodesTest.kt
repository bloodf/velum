package dev.velum.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ForbiddenOpcodesTest {
    @Test
    fun knownDestructiveOpcodesAreForbidden() {
        listOf(5, 104, 128, 0xFE20, 143).forEach { op ->
            assertTrue(ForbiddenOpcodes.isForbidden(op), "expected $op forbidden")
            assertFailsWith<IllegalArgumentException> {
                ForbiddenOpcodes.requireAllowed(op)
            }
        }
        assertTrue(ForbiddenOpcodes.RESET_FINDMY in ForbiddenOpcodes.ALL)
    }

    @Test
    fun plaudOutboundGuard_rejectsForbiddenOpcodesAndSettings() {
        fun raw(cmd: Int, params: ByteArray = ByteArray(0)): ByteArray {
            val packet = ByteArray(1 + 2 + params.size)
            packet[0] = Framing.APP_PREFIX
            Bytes.u16Le(cmd).copyInto(packet, 1)
            params.copyInto(packet, 3)
            return packet
        }
        ForbiddenOpcodes.ALL.forEach { op ->
            assertFailsWith<IllegalArgumentException> {
                PlaudOutboundGuard.requireAllowed(raw(op))
            }
        }
        assertFailsWith<IllegalArgumentException> {
            PlaudOutboundGuard.requireAllowed(raw(8, byteArrayOf(4, 1)))
        }
        assertFailsWith<IllegalArgumentException> {
            PlaudOutboundGuard.requireAllowed(raw(8, byteArrayOf(26, 1)))
        }
        PlaudOutboundGuard.requireAllowed(raw(9))
        PlaudOutboundGuard.requireAllowed(raw(8, byteArrayOf(4))) // read of setting 4 OK
    }

    @Test
    fun fe20_detectionUsesLittleEndianOpcodeEncoding() {
        // Plaud framing encodes opcodes with Bytes.u16Le / Bytes.readU16Le
        // (see Framing.encodeCommand and PlaudOutboundGuard). 0xFE20 therefore
        // appears on the wire as little-endian bytes [0x20, 0xFE], not big-endian
        // [0xFE, 0x20].
        val le = Bytes.u16Le(ForbiddenOpcodes.FE20)
        assertEquals(0x20, le[0].toInt() and 0xFF)
        assertEquals(0xFE, le[1].toInt() and 0xFF)
        assertEquals(ForbiddenOpcodes.FE20, Bytes.readU16Le(le, 0))
        assertFailsWith<IllegalArgumentException> {
            PlaudOutboundGuard.requireAllowed(le)
        }
        assertFailsWith<IllegalArgumentException> {
            PlaudOutboundGuard.requireAllowed(
                byteArrayOf(Framing.APP_PREFIX) + le,
            )
        }

        // Big-endian layout [0xFE, 0x20] reads as 0x20FE via readU16Le — not FE20.
        val beLayout = byteArrayOf(0xFE.toByte(), 0x20)
        assertEquals(0x20FE, Bytes.readU16Le(beLayout, 0))
        assertFalse(ForbiddenOpcodes.isForbidden(0x20FE))
        PlaudOutboundGuard.requireAllowed(beLayout)
        PlaudOutboundGuard.requireAllowed(byteArrayOf(Framing.APP_PREFIX) + beLayout)
    }

    @Test
    fun safeOpcodesAllowed() {
        listOf(1, 9, 26, 28, 29, 0xFE10, 0xFE11, 0xFE12, 138).forEach { op ->
            assertFalse(ForbiddenOpcodes.isForbidden(op))
            ForbiddenOpcodes.requireAllowed(op)
        }
    }

    @Test
    fun encodeCommand_rejectsForbidden() {
        assertFailsWith<IllegalArgumentException> {
            Framing.encodeCommand(ForbiddenOpcodes.CLEAR_ALL)
        }
        assertFailsWith<IllegalArgumentException> {
            Framing.encodeCommand(ForbiddenOpcodes.OTA)
        }
        assertFailsWith<IllegalArgumentException> {
            Framing.encodeCommand(ForbiddenOpcodes.RESET_FINDMY)
        }
    }

    @Test
    fun encodeChunks_rejectsFe20() {
        assertFailsWith<IllegalArgumentException> {
            Unlock.encodeChunks(ForbiddenOpcodes.FE20, ByteArray(16) { 1 })
        }
    }

    @Test
    fun forbiddenSettings_neverWritable() {
        assertTrue(ForbiddenSettings.isForbidden(4))
        assertTrue(ForbiddenSettings.isForbidden(26))
        assertFailsWith<IllegalArgumentException> {
            CommonSettings.encodeWrite(ForbiddenSettings.AUTO_DELETE, byteArrayOf(1))
        }
        assertFailsWith<IllegalArgumentException> {
            CommonSettings.encodeWrite(ForbiddenSettings.AUTO_SYNC, byteArrayOf(1))
        }
        val ok = CommonSettings.encodeWrite(1, byteArrayOf(0x0A))
        assertEquals(Framing.APP_PREFIX, ok[0])
        assertEquals(8, ok[1].toInt() and 0xFF)
        // Reads of forbidden ids remain framed (only writes are gated).
        val read = CommonSettings.encodeRead(ForbiddenSettings.AUTO_DELETE)
        assertEquals(Framing.APP_PREFIX, read[0])
        assertEquals(CommonSettings.CMD, Bytes.readU16Le(read, 1))
        assertEquals(1, read.size - 3)
        assertEquals(ForbiddenSettings.AUTO_DELETE, read[3].toInt() and 0xFF)
    }

    @Test
    fun unusedCommands_documented() {
        assertEquals(
            setOf(5, 13, 38, 50, 51, 109, 141, 142, 145, 151),
            UnusedCommands.ALL,
        )
        UnusedCommands.ALL.forEach { op ->
            assertTrue(UnusedCommands.isUnused(op))
        }
    }

    @Test
    fun featureRequest_bit08_aesGcm() {
        val withBit = ByteArray(4) { 0 }
        withBit[3] = 0x08
        assertTrue(FeatureRequest.parse(withBit).wifiAesGcm)
        assertFalse(FeatureRequest.parse(ByteArray(4)).wifiAesGcm)
        assertFalse(FeatureRequest.parse(ByteArray(2)).wifiAesGcm)
    }

    @Test
    fun noteProFamily_883_noOta() {
        assertEquals(DeviceModel.NotePro883, DeviceModel.fromCode(883))
        assertTrue(DeviceModel.NotePro.isNoteProFamily)
        assertTrue(DeviceModel.NotePro883.isNoteProFamily)
        assertFalse(DeviceModel.NotePro.supportsOta)
        assertFalse(DeviceModel.NotePro883.supportsOta)
        val table = CommandTable.forModel(DeviceModel.NotePro883, 20)
        assertTrue(table.usesRsaUnlock)
        assertEquals(4, table.setClock)
    }

    @Test
    fun encodeBatteryRequest_isCmd9() {
        val frame = Battery.encodeBatteryRequest()
        assertEquals(Framing.APP_PREFIX, frame[0])
        assertEquals(Commands.BATTERY_LIKELY, frame[1].toInt() and 0xFF)
    }
}
