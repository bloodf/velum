package dev.velum.ble

import dev.velum.ble.fake.FakeBleTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield

class FakeBleTransportMultiGattTest {
    @Test
    fun connect_setsActiveProfile_andRecordsWritesPerCharacteristic() = runTest {
        val ble = FakeBleTransport()
        ble.connect("fake-note-pro", GattProfiles.Pocket)
        assertEquals("pocket", ble.activeProfile?.id)

        ble.write(GattProfiles.POCKET_CMD_WRITE, byteArrayOf(1, 2, 3))
        ble.write(GattProfiles.POCKET_CMD_NOTIFY, byteArrayOf(4, 5))

        assertEquals(2, ble.recordedWrites.size)
        assertEquals(GattProfiles.POCKET_CMD_WRITE, ble.recordedWrites[0].first)
        assertTrue(ble.recordedWrites[0].second.contentEquals(byteArrayOf(1, 2, 3)))
        assertEquals(GattProfiles.POCKET_CMD_NOTIFY, ble.recordedWrites[1].first)
        assertTrue(ble.recordedWrites[1].second.contentEquals(byteArrayOf(4, 5)))
    }

    @Test
    fun notifications_arePerCharacteristic() = runTest {
        val ble = FakeBleTransport()
        ble.connect("dev", GattProfiles.Omi)

        val audio = mutableListOf<ByteArray>()
        val codec = mutableListOf<ByteArray>()
        val audioJob = launch {
            ble.notifications(GattProfiles.OMI_AUDIO_DATA).take(1).toList(audio)
        }
        val codecJob = launch {
            ble.notifications(GattProfiles.OMI_AUDIO_CODEC).take(1).toList(codec)
        }
        yield()
        ble.emitNotification(GattProfiles.OMI_AUDIO_DATA, byteArrayOf(9, 8, 7))
        ble.emitNotification(GattProfiles.OMI_AUDIO_CODEC, byteArrayOf(1))
        audioJob.join()
        codecJob.join()

        assertEquals(1, audio.size)
        assertTrue(audio[0].contentEquals(byteArrayOf(9, 8, 7)))
        assertEquals(1, codec.size)
        assertTrue(codec[0].contentEquals(byteArrayOf(1)))
    }

    @Test
    fun unknownCharacteristic_rejected() = runTest {
        val ble = FakeBleTransport()
        ble.connect("dev", GattProfiles.Limitless)
        assertFailsWith<IllegalStateException> {
            ble.write("no-such-char", byteArrayOf(1))
        }
    }

    @Test
    fun plaudDefaultConnect_usesPlaudProfile() = runTest {
        val ble = FakeBleTransport()
        ble.connect("fake-note-pro")
        assertEquals(GattProfiles.Plaud.id, ble.activeProfile?.id)
        ble.write(GattProfiles.PLAUD_WRITE, byteArrayOf(0x01, 0x09, 0x00))
        assertEquals(1, ble.recordedWrites.size)
        assertEquals(GattProfiles.PLAUD_WRITE, ble.recordedWrites[0].first)
    }

    @Test
    fun reconnect_clearsWrites_andSwitchesProfile() = runTest {
        val ble = FakeBleTransport()
        ble.connect("dev", GattProfiles.Omi)
        ble.emitNotification(GattProfiles.OMI_AUDIO_DATA, byteArrayOf(1))
        ble.disconnect()
        ble.connect("dev", GattProfiles.Limitless)
        assertEquals("limitless", ble.activeProfile?.id)
        assertTrue(ble.recordedWrites.isEmpty())
        ble.write(GattProfiles.LIMITLESS_TX, byteArrayOf(0x0A))
        assertEquals(GattProfiles.LIMITLESS_TX, ble.recordedWrites.single().first)
    }
}
