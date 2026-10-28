package dev.velum.ble

import dev.velum.protocol.PlaudUuids
import dev.velum.recorder.omi.OmiUuids
import dev.velum.recorder.pocket.PocketUuids
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GattProfileTest {
    @Test
    fun plaudProfile_matchesKnownUuids() {
        val p = GattProfiles.Plaud
        assertEquals("plaud", p.id)
        assertEquals(PlaudUuids.SERVICE, p.serviceUuid)
        assertEquals(PlaudUuids.WRITE, p.characteristic(GattProfiles.PLAUD_WRITE).uuid)
        assertEquals(PlaudUuids.NOTIFY, p.characteristic(GattProfiles.PLAUD_NOTIFY).uuid)
        assertTrue(p.characteristic(GattProfiles.PLAUD_WRITE).canWrite)
        assertTrue(p.characteristic(GattProfiles.PLAUD_NOTIFY).canNotify)
        assertEquals(1, p.writeCharacteristics().size)
        assertEquals(1, p.notifyCharacteristics().size)
        assertNotNull(p.preferredMtu)
    }

    @Test
    fun omiProfile_matchesOmiUuids() {
        val p = GattProfiles.Omi
        assertEquals("omi", p.id)
        assertEquals(OmiUuids.SERVICE, p.serviceUuid)
        assertEquals(OmiUuids.AUDIO_DATA, p.characteristic(GattProfiles.OMI_AUDIO_DATA).uuid)
        assertEquals(OmiUuids.AUDIO_CODEC, p.characteristic(GattProfiles.OMI_AUDIO_CODEC).uuid)
        assertEquals(OmiUuids.LIGHT_CODEC, p.characteristic(GattProfiles.OMI_LIGHT_CODEC).uuid)
        assertTrue(p.notifyCharacteristics().isNotEmpty())
        assertTrue(p.writeCharacteristics().isEmpty())
    }

    @Test
    fun pocketProfile_matchesPocketUuids() {
        val p = GattProfiles.Pocket
        assertEquals("pocket", p.id)
        assertEquals(PocketUuids.AUDIO_SERVICE, p.serviceUuid)
        assertEquals(PocketUuids.AUDIO_WRITE, p.characteristic(GattProfiles.POCKET_CMD_WRITE).uuid)
        assertEquals(PocketUuids.AUDIO_META, p.characteristic(GattProfiles.POCKET_CMD_NOTIFY).uuid)
        assertEquals(PocketUuids.AUDIO_DATA, p.characteristic(GattProfiles.POCKET_AUDIO_NOTIFY).uuid)
        assertTrue(p.writeCharacteristics().any { it.name == GattProfiles.POCKET_CMD_WRITE })
        assertTrue(p.notifyCharacteristics().any { it.name == GattProfiles.POCKET_AUDIO_NOTIFY })
    }

    @Test
    fun limitlessProfile_usesUuidsFromOmiModelsDart() {
        // Source: BasedHardware/omi app/lib/services/devices/models.dart via OmiUuids.
        val p = GattProfiles.Limitless
        assertEquals("limitless", p.id)
        assertEquals(OmiUuids.LIMITLESS_SERVICE, p.serviceUuid)
        assertEquals(OmiUuids.LIMITLESS_TX, p.characteristic(GattProfiles.LIMITLESS_TX).uuid)
        assertEquals(OmiUuids.LIMITLESS_RX, p.characteristic(GattProfiles.LIMITLESS_RX).uuid)
        assertTrue(p.characteristic(GattProfiles.LIMITLESS_TX).canWrite)
        assertTrue(p.characteristic(GattProfiles.LIMITLESS_RX).canNotify)
    }

    @Test
    fun byId_andUnknownCharacteristic() {
        assertEquals(GattProfiles.Plaud, GattProfiles.byId("plaud"))
        assertEquals(GattProfiles.Omi, GattProfiles.byId("omi"))
        assertEquals(GattProfiles.Pocket, GattProfiles.byId("pocket"))
        assertEquals(GattProfiles.Limitless, GattProfiles.byId("limitless"))
        assertEquals(4, GattProfiles.All.size)
        assertFailsWith<IllegalStateException> { GattProfiles.byId("nope") }
        assertFailsWith<IllegalStateException> {
            GattProfiles.Plaud.characteristic("missing")
        }
    }
}
