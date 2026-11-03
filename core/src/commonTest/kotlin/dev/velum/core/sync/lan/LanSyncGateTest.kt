package dev.velum.core.sync.lan

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ProvisionalLanIdentity::class)
class LanSyncGateTest {
    private val PHONE = "1".repeat(32)
    private val OTHER = "2".repeat(32)
    private val TABLET = "3".repeat(32)
    private val STRANGER = "4".repeat(32)
    private val keyA = "a".repeat(64)
    private val keyB = "b".repeat(64)
    private val phone = PairedDevice(PHONE, "Phone", keyA)
    private val devices = PairedDevices().pair(phone)

    @Test fun off_by_default_and_with_no_paired_device_nothing_listens() {
        assertFalse(LanSyncGate.mayListenOrAdvertise(enabled = false, devices = devices))
        assertFalse(LanSyncGate.mayListenOrAdvertise(enabled = true, devices = PairedDevices()))
        assertFalse(LanSyncGate.mayListenOrAdvertise(enabled = true, devices = devices.revoke(PHONE)))
        assertTrue(LanSyncGate.mayListenOrAdvertise(enabled = true, devices = devices))
    }

    @Test fun admits_only_the_pinned_key_for_an_active_device() {
        assertEquals(LanAdmission.Accept(phone), LanSyncGate.admit(true, devices, PHONE, keyA))
        assertEquals(LanAdmission.Reject(LanRejectReason.KeyMismatch), LanSyncGate.admit(true, devices, PHONE, keyB))
        assertEquals(LanAdmission.Reject(LanRejectReason.UnknownDevice), LanSyncGate.admit(true, devices, OTHER, keyA))
        assertEquals(LanAdmission.Reject(LanRejectReason.Disabled), LanSyncGate.admit(false, devices, PHONE, keyA))
    }

    @Test fun revoked_device_can_no_longer_sync_even_with_its_old_key() {
        val revoked = devices.revoke(PHONE)
        assertEquals(LanAdmission.Reject(LanRejectReason.Revoked), LanSyncGate.admit(true, revoked, PHONE, keyA))
        // Re-pairing is an explicit new ceremony that replaces the entry.
        val repaired = revoked.pair(PairedDevice(PHONE, "Phone", keyB))
        assertTrue(LanSyncGate.admit(true, repaired, PHONE, keyB) is LanAdmission.Accept)
        assertTrue(LanSyncGate.admit(true, repaired, PHONE, keyA) is LanAdmission.Reject)
    }

    @Test fun rename_and_revoke_touch_only_their_device() {
        val two = devices.pair(PairedDevice(TABLET, "Tablet", keyB)).rename(TABLET, "Desk tablet").revoke(PHONE)
        assertEquals("Desk tablet", two.find(TABLET)?.name)
        assertEquals(listOf(TABLET), two.active.map { it.id })
        assertEquals(2, two.all.size)
    }

    @Test fun device_values_are_validated() {
        assertFailsWith<IllegalArgumentException> { PairedDevice("x", "Phone", keyA) }
        assertFailsWith<IllegalArgumentException> { PairedDevice(PHONE, " ", keyA) }
        assertFailsWith<IllegalArgumentException> { PairedDevice(PHONE, "Phone", "zz") }
    }

    @Test fun advertisement_round_trips_and_rejects_malformed_txt() {
        val ad = LanAdvertisement(PHONE, 4242)
        assertEquals(ad, LanAdvertisement.fromTxt(ad.toTxt()))
        val bad = listOf(
            ad.toTxt() + ("v" to "2"), ad.toTxt() - "v", ad.toTxt() + ("id" to "a b"), ad.toTxt() + ("id" to "short"),
            ad.toTxt() + ("port" to "0"), ad.toTxt() + ("port" to "65536"), ad.toTxt() + ("port" to "-1"),
            ad.toTxt() + ("port" to "99999999999"), ad.toTxt() + ("port" to "4a"), ad.toTxt() - "port",
        )
        bad.forEach { assertNull(LanAdvertisement.fromTxt(it), it.toString()) }
    }

    @Test fun only_active_paired_ads_are_dialed_and_only_when_enabled() {
        val seen = listOf(LanAdvertisement(PHONE, 1111), LanAdvertisement(STRANGER, 2222))
        assertEquals(listOf(seen[0]), LanPeers.dialable(true, devices, seen))
        assertTrue(LanPeers.dialable(false, devices, seen).isEmpty())
        assertTrue(LanPeers.dialable(true, devices.revoke(PHONE), seen).isEmpty())
    }

    @Test fun manual_endpoint_accepts_only_private_ipv4_literals() {
        assertEquals(LanEndpoint("192.168.1.5", 4242), LanEndpoint.parse(" 192.168.1.5:4242 "))
        assertEquals(LanEndpoint("10.0.0.7", 80), LanEndpoint.parse("10.0.0.7:80"))
        assertEquals(LanEndpoint("172.16.0.1", 9), LanEndpoint.parse("172.16.0.1:9"))
        assertEquals(LanEndpoint("169.254.1.1", 9), LanEndpoint.parse("169.254.1.1:9"))
        listOf(
            "8.8.8.8:80", "172.32.0.1:80", "192.169.1.1:80", "example.com:80", "192.168.1.5", "192.168.1.5:0",
            "192.168.1.5:65536", "192.168.1.256:80", "[fe80::1]:80", "192.168.1:80", "192.168.1.5:80:1", "",
        ).forEach { assertNull(LanEndpoint.parse(it), it) }
    }

    @Test fun empty_and_non_ascii_numbers_never_throw_and_never_parse() {
        assertNull(LanAdvertisement.fromTxt(mapOf("v" to "1", "id" to PHONE, "port" to "")))
        assertNull(LanEndpoint.parse("192.168.1.5:"))
        assertNull(LanEndpoint.parse("192.168.1.5:\u0664\u0662"))      // Arabic-Indic digits
        assertNull(LanEndpoint.parse("192.168.1.\u0665:80"))
        assertNull(LanAdvertisement.fromTxt(mapOf("v" to "1", "id" to PHONE, "port" to "\u0664\u0662\u0664\u0662")))
        assertNull(LanAdvertisement.fromTxt(mapOf("v" to "1", "id" to PHONE, "port" to "04242")))
        assertNull(LanEndpoint.parse("192.168.01.5:80"))
        assertNull(LanEndpoint.parse("192.168.1.5:080"))
    }

    @Test fun unusable_lan_addresses_are_rejected() {
        listOf("169.254.169.254:80", "192.168.1.0:80", "192.168.1.255:80", "10.0.0.255:80", "255.255.255.255:80")
            .forEach { assertNull(LanEndpoint.parse(it), it) }
    }

    @Test fun constructors_validate_their_values() {
        assertFailsWith<IllegalArgumentException> { LanEndpoint("8.8.8.8", 80) }
        assertFailsWith<IllegalArgumentException> { LanEndpoint("192.168.1.5", 0) }
        assertFailsWith<IllegalArgumentException> { LanAdvertisement(PHONE, 70_000) }
        assertFailsWith<IllegalArgumentException> { LanAdvertisement("SN-12345678", 80) }
    }

    @Test fun manual_dial_is_gated_like_discovery() {
        assertEquals(LanEndpoint("192.168.1.5", 80), LanPeers.manualDial(true, devices, "192.168.1.5:80"))
        assertNull(LanPeers.manualDial(false, devices, "192.168.1.5:80"))
        assertNull(LanPeers.manualDial(true, PairedDevices(), "192.168.1.5:80"))
        assertNull(LanPeers.manualDial(true, devices, "8.8.8.8:80"))
    }

    @Test fun keys_are_unique_and_a_revoked_key_is_never_trusted_again() {
        assertFailsWith<IllegalArgumentException> { devices.pair(PairedDevice(TABLET, "Tablet", keyA)) }
        val revoked = devices.revoke(PHONE)
        assertFailsWith<IllegalArgumentException> { revoked.pair(PairedDevice(PHONE, "Phone", keyA)) }
        assertFailsWith<IllegalArgumentException> { revoked.pair(PairedDevice(TABLET, "Tablet", keyA)) }
        assertFailsWith<IllegalArgumentException> { revoked.pair(PairedDevice(TABLET, "Tablet", keyB, revoked = true)) }
        assertFailsWith<IllegalArgumentException> { PairedDevices(listOf(phone, PairedDevice(TABLET, "T", keyA))) }
    }

    @Test fun hardware_shaped_ids_are_rejected() {
        listOf("aa:bb:cc:dd:ee:ff", "SN1234567890", "R5CT1234567", "353879234252633", "A".repeat(32), "1".repeat(31), "1".repeat(33))
            .forEach { assertFailsWith<IllegalArgumentException>(it) { PairedDevice(it, "Phone", keyA) } }
    }

    @Test fun names_lose_control_and_bidi_characters() {
        assertEquals("Phone", PairedDevice.cleanName("\u202EPho\u0000ne\u2066 "))
        assertEquals("Desk", devices.rename(PHONE, "\u202E Desk\n").find(PHONE)?.name)
        assertFailsWith<IllegalArgumentException> { PairedDevice(PHONE, "\u202Eevil", keyA) }
        assertFailsWith<IllegalArgumentException> { PairedDevice(PHONE, "\u202E", keyA) }
    }
}
