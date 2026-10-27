package dev.velum.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeviceCapabilityRegistryTest {

    @Test
    fun productCodes_mapToExpectedModels() {
        assertEquals(DeviceModel.PlaudOne, DeviceCapabilityRegistry.forProductCode(860).model)
        assertEquals(DeviceModel.NotePin, DeviceCapabilityRegistry.forProductCode(880).model)
        assertEquals(DeviceModel.NotePro, DeviceCapabilityRegistry.forProductCode(881).model)
        assertEquals(DeviceModel.NotePinS, DeviceCapabilityRegistry.forProductCode(882).model)
        assertEquals(DeviceModel.NotePro883, DeviceCapabilityRegistry.forProductCode(883).model)
        assertEquals(DeviceModel.Note, DeviceCapabilityRegistry.forProductCode(888).model)
    }

    @Test
    fun notePro_hasWifiFastPath_noteDoesNot() {
        assertTrue(DeviceCapabilityRegistry.supportsWifiFastPath(DeviceCapabilityRegistry.forModel(DeviceModel.NotePro)))
        assertTrue(DeviceCapabilityRegistry.supportsWifiFastPath(DeviceCapabilityRegistry.forModel(DeviceModel.NotePin)))
        assertFalse(DeviceCapabilityRegistry.supportsWifiFastPath(DeviceCapabilityRegistry.forModel(DeviceModel.Note)))
    }

    @Test
    fun detect_byAdvertisedName_whenCodeMissing() {
        val pin = DeviceCapabilityRegistry.detect(productCode = null, advertisedName = "NotePin-ABC")
        assertEquals(DeviceModel.NotePin, pin.model)

        val pro = DeviceCapabilityRegistry.detect(productCode = null, advertisedName = "Note Pro 1234")
        assertEquals(DeviceModel.NotePro, pro.model)
    }

    @Test
    fun detect_prefersProductCodeOverName() {
        val caps = DeviceCapabilityRegistry.detect(
            productCode = 888,
            advertisedName = "NotePin Fake",
        )
        assertEquals(DeviceModel.Note, caps.model)
    }

    @Test
    fun handshake_disablesWifiWhenDeviceReportsNoWifi() {
        val base = DeviceCapabilityRegistry.forModel(DeviceModel.NotePro)
        assertTrue(base.wifiFastTransfer)
        val hs = HandshakeResponse(
            status = HandshakeStatus.Ok,
            protocolVersion = 20,
            timezoneHours = 0,
            timezoneMinutes = 0,
            audioChannels = 1,
            wifiSupported = false,
            noNsAgc = false,
            oggAudio = true,
            rawPayload = ByteArray(9),
        )
        val refined = base.withHandshake(hs)
        assertFalse(refined.wifiFastTransfer)
        assertFalse(DeviceCapabilityRegistry.supportsWifiFastPath(refined))
    }

    @Test
    fun everyKnownModel_stillForbidsDestructiveOpcodes() {
        DeviceCapabilityRegistry.all().forEach { caps ->
            ForbiddenOpcodes.ALL.forEach { op ->
                assertTrue(
                    ForbiddenOpcodes.isForbidden(op),
                    "${caps.displayName} must still forbid $op",
                )
            }
            assertTrue(ForbiddenSettings.isForbidden(4))
            assertTrue(ForbiddenSettings.isForbidden(26))
        }
    }

    @Test
    fun newerUnknownCode_stillDetectableAsProtocolCompatible() {
        val caps = DeviceCapabilityRegistry.detect(productCode = 999, advertisedName = "Plaud Future")
        assertEquals(DeviceModel.Unknown, caps.model)
        assertTrue(caps.displayName.isNotBlank())
        assertTrue(caps.wifiFastTransfer) // refined by handshake later
    }
}
