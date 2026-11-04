package dev.velum.ble.gatt

import dev.velum.ble.BleConnectionState
import dev.velum.ble.fake.FakeBleTransport
import dev.velum.ble.fake.FakePlaudPeripheral
import dev.velum.ble.crypto.createSessionCipher
import dev.velum.ble.crypto.rsaKeyPairToPem
import dev.velum.protocol.DeviceIdentityKeys
import dev.velum.recorder.omi.OmiUuids
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** #19: the Limitless profile only addresses the notify stream and can never write. No driver is built on it. */
class LimitlessProfileTest {
    private val txChar = GattChar(OmiUuids.LIMITLESS_SERVICE, OmiUuids.LIMITLESS_TX)
    private val rxChar = GattChar(OmiUuids.LIMITLESS_SERVICE, OmiUuids.LIMITLESS_RX)

    /** A fake transport that counts the writes that reach it (the hook runs before each one). */
    private class Counting(val ble: FakeBleTransport) {
        var writes = 0
        init { ble.beforeWrite = { writes++ } }
    }

    private fun fake(): FakeBleTransport {
        val pems = rsaKeyPairToPem()
        val keys = DeviceIdentityKeys("881SYNTH", ByteArray(256), pems.first, pems.second, "t")
        return FakeBleTransport(FakePlaudPeripheral(createSessionCipher(), keys, ByteArray(4), requirePublicKey = false))
    }

    @Test fun the_profile_is_notify_only_and_addressed_by_the_published_uuids() {
        assertTrue(GattProfiles.Limitless.writes.isEmpty())
        assertEquals(listOf(rxChar), GattProfiles.Limitless.notify)
        assertEquals("632de003-604c-446b-a80f-7963e950f3fb", GattProfiles.LIMITLESS_RX.characteristic)
        assertEquals("632de002-604c-446b-a80f-7963e950f3fb", GattProfiles.LIMITLESS_TX.characteristic)
    }

    @Test fun every_write_under_the_profile_is_refused_including_the_command_channel_and_plauds_char() = runTest {
        val sink = Counting(fake())
        val link = GuardedLink(sink.ble, GattProfiles.Limitless)
        val payloads = listOf(byteArrayOf(0x08, 0x01), byteArrayOf(0x12, 0x00), byteArrayOf(1))
        for (char in listOf(txChar, rxChar, GattProfiles.PLAUD_WRITE)) {
            for (p in payloads) assertFailsWith<IllegalArgumentException>("$char") { link.write(char, p, withoutResponse = false) }
        }
        assertEquals(0, sink.writes)
    }

    @Test fun other_profiles_cannot_write_to_the_limitless_characteristics() = runTest {
        val sink = Counting(fake())
        for (profile in listOf(GattProfiles.Plaud, GattProfiles.Omi, GattProfiles.Pocket)) {
            val link = GuardedLink(sink.ble, profile)
            for (char in listOf(txChar, rxChar)) {
                assertFailsWith<IllegalArgumentException>("${profile.id} $char") { link.write(char, byteArrayOf(1), withoutResponse = true) }
            }
        }
        assertEquals(0, sink.writes)
    }

    @Test fun a_transport_refuses_to_connect_for_a_profile_it_has_no_driver_path_for() = runTest {
        for (profile in listOf(GattProfiles.Limitless, GattProfiles.Omi, GattProfiles.Pocket)) {
            val ble = fake()
            assertFailsWith<IllegalArgumentException>(profile.id) { ble.connect("fake-note-pro", profile) }
            assertEquals(BleConnectionState.Idle, ble.connectionState.value, "nothing was connected for ${profile.id}")
        }
    }

    @Test fun the_plaud_profile_connects_through_the_profile_overload() = runTest {
        val ble = fake()
        ble.connect("fake-note-pro", GattProfiles.Plaud)
        assertTrue(ble.connectionState.value is BleConnectionState.Connected)
    }
}
