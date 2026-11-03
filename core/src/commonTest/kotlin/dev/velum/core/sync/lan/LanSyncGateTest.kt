package dev.velum.core.sync.lan

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LanSyncGateTest {
    private val keyA = "a".repeat(64)
    private val keyB = "b".repeat(64)
    private val phone = PairedDevice("phone-0001", "Phone", keyA)
    private val devices = PairedDevices().pair(phone)

    @Test fun off_by_default_and_with_no_paired_device_nothing_listens() {
        assertFalse(LanSyncGate.mayListenOrAdvertise(enabled = false, devices = devices))
        assertFalse(LanSyncGate.mayListenOrAdvertise(enabled = true, devices = PairedDevices()))
        assertFalse(LanSyncGate.mayListenOrAdvertise(enabled = true, devices = devices.revoke("phone-0001")))
        assertTrue(LanSyncGate.mayListenOrAdvertise(enabled = true, devices = devices))
    }

    @Test fun admits_only_the_pinned_key_for_an_active_device() {
        assertEquals(LanAdmission.Accept(phone), LanSyncGate.admit(true, devices, "phone-0001", keyA))
        assertEquals(LanAdmission.Reject(LanRejectReason.KeyMismatch), LanSyncGate.admit(true, devices, "phone-0001", keyB))
        assertEquals(LanAdmission.Reject(LanRejectReason.UnknownDevice), LanSyncGate.admit(true, devices, "other-0002", keyA))
        assertEquals(LanAdmission.Reject(LanRejectReason.Disabled), LanSyncGate.admit(false, devices, "phone-0001", keyA))
    }

    @Test fun revoked_device_can_no_longer_sync_even_with_its_old_key() {
        val revoked = devices.revoke("phone-0001")
        assertEquals(LanAdmission.Reject(LanRejectReason.Revoked), LanSyncGate.admit(true, revoked, "phone-0001", keyA))
        // Re-pairing is an explicit new ceremony that replaces the entry.
        val repaired = revoked.pair(PairedDevice("phone-0001", "Phone", keyB))
        assertTrue(LanSyncGate.admit(true, repaired, "phone-0001", keyB) is LanAdmission.Accept)
        assertTrue(LanSyncGate.admit(true, repaired, "phone-0001", keyA) is LanAdmission.Reject)
    }

    @Test fun rename_and_revoke_touch_only_their_device() {
        val two = devices.pair(PairedDevice("tablet-0003", "Tablet", keyB)).rename("tablet-0003", "Desk tablet").revoke("phone-0001")
        assertEquals("Desk tablet", two.find("tablet-0003")?.name)
        assertEquals(listOf("tablet-0003"), two.active.map { it.id })
        assertEquals(2, two.all.size)
    }

    @Test fun device_values_are_validated() {
        assertFailsWith<IllegalArgumentException> { PairedDevice("x", "Phone", keyA) }
        assertFailsWith<IllegalArgumentException> { PairedDevice("phone-0001", " ", keyA) }
        assertFailsWith<IllegalArgumentException> { PairedDevice("phone-0001", "Phone", "zz") }
    }

    @Test fun advertisement_round_trips_and_rejects_malformed_txt() {
        val ad = LanAdvertisement("phone-0001", 4242)
        assertEquals(ad, LanAdvertisement.fromTxt(ad.toTxt()))
        val bad = listOf(
            ad.toTxt() + ("v" to "2"), ad.toTxt() - "v", ad.toTxt() + ("id" to "a b"), ad.toTxt() + ("id" to "short"),
            ad.toTxt() + ("port" to "0"), ad.toTxt() + ("port" to "65536"), ad.toTxt() + ("port" to "-1"),
            ad.toTxt() + ("port" to "99999999999"), ad.toTxt() + ("port" to "4a"), ad.toTxt() - "port",
        )
        bad.forEach { assertNull(LanAdvertisement.fromTxt(it), it.toString()) }
    }

    @Test fun only_active_paired_ads_are_dialed_and_only_when_enabled() {
        val seen = listOf(LanAdvertisement("phone-0001", 1111), LanAdvertisement("stranger-9", 2222))
        assertEquals(listOf(seen[0]), LanPeers.dialable(true, devices, seen))
        assertTrue(LanPeers.dialable(false, devices, seen).isEmpty())
        assertTrue(LanPeers.dialable(true, devices.revoke("phone-0001"), seen).isEmpty())
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
}
