package dev.velum.core.sync.lan

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ProvisionalLanIdentity::class)
class LanSyncGateTest {
    private val phoneId = "1".repeat(32)
    private val otherId = "2".repeat(32)
    private val tabletId = "3".repeat(32)
    private val strangerId = "4".repeat(32)
    private val keyA = "a".repeat(64)
    private val keyB = "b".repeat(64)
    private val keyC = "c".repeat(64)
    private val phone = PairedDevice.create(phoneId, "Phone", keyA)
    private val tablet = PairedDevice.create(tabletId, "Tablet", keyB)
    private val devices = PairedDevices.EMPTY.pair(phone)

    private fun txt(id: String, port: Int) = LanAdvertisement.forSelf(id, port).toTxt()

    @Test fun nothing_listens_when_disabled_unpaired_or_all_revoked() {
        assertFalse(LanSyncGate.mayListenOrAdvertise(enabled = false, devices = devices))
        assertFalse(LanSyncGate.mayListenOrAdvertise(enabled = true, devices = PairedDevices.EMPTY))
        assertFalse(LanSyncGate.mayListenOrAdvertise(enabled = true, devices = devices.revoke(phoneId)))
        assertTrue(LanSyncGate.mayListenOrAdvertise(enabled = true, devices = devices))
    }

    @Test fun admits_only_the_pinned_key_for_an_active_device() {
        assertEquals(LanAdmission.Accept(phone), LanSyncGate.admit(true, devices, phoneId, keyA))
        assertEquals(LanAdmission.Reject(LanRejectReason.KeyMismatch), LanSyncGate.admit(true, devices, phoneId, keyB))
        assertEquals(LanAdmission.Reject(LanRejectReason.UnknownDevice), LanSyncGate.admit(true, devices, otherId, keyA))
        assertEquals(LanAdmission.Reject(LanRejectReason.Disabled), LanSyncGate.admit(false, devices, phoneId, keyA))
    }

    @Test fun a_key_compare_always_runs_even_for_unknown_ids() {
        val calls = mutableListOf<Pair<String, String>>()
        val spy: (String, String) -> Boolean = { a, b -> calls += a to b; a == b }
        LanSyncGate.admit(true, devices, phoneId, keyB, spy)
        LanSyncGate.admit(true, devices, otherId, keyB, spy)
        assertEquals(listOf(keyA to keyB, "0".repeat(64) to keyB), calls)
    }

    @Test fun revoked_device_can_no_longer_sync_even_with_its_old_key() {
        val revoked = devices.revoke(phoneId)
        assertEquals(LanAdmission.Reject(LanRejectReason.Revoked), LanSyncGate.admit(true, revoked, phoneId, keyA))
        val repaired = revoked.pair(PairedDevice.create(phoneId, "Phone", keyB))
        assertTrue(LanSyncGate.admit(true, repaired, phoneId, keyB) is LanAdmission.Accept)
        assertTrue(LanSyncGate.admit(true, repaired, phoneId, keyA) is LanAdmission.Reject)
    }

    @Test fun a_retired_key_never_comes_back_by_any_sequence() {
        val revoked = devices.revoke(phoneId)
        // Replace the revoked row with a new key, then try the old key again, under the same id and another.
        val swapped = revoked.pair(PairedDevice.create(phoneId, "Phone", keyB))
        assertFailsWith<IllegalArgumentException> { swapped.pair(PairedDevice.create(phoneId, "Phone", keyA)) }
        assertFailsWith<IllegalArgumentException> { swapped.pair(PairedDevice.create(otherId, "Other", keyA)) }
        // The same on an active id whose key was replaced.
        val replaced = devices.pair(PairedDevice.create(phoneId, "Phone", keyB))
        assertFailsWith<IllegalArgumentException> { replaced.pair(PairedDevice.create(phoneId, "Phone", keyA)) }
        assertFailsWith<IllegalArgumentException> { replaced.pair(PairedDevice.create(otherId, "Other", keyA)) }
        // Revoked, then simply paired again with its own key.
        assertFailsWith<IllegalArgumentException> { revoked.pair(PairedDevice.create(phoneId, "Phone", keyA)) }
        assertFailsWith<IllegalArgumentException> { revoked.pair(PairedDevice.create(otherId, "Other", keyA)) }
        assertTrue(LanSyncGate.admit(true, swapped, phoneId, keyA) is LanAdmission.Reject)
    }

    @Test fun persistence_restore_keeps_revocations_and_rejects_a_retired_key_made_active() {
        val state = devices.pair(tablet).revoke(phoneId)
        val back = PairedDevices.restore(state.all, state.retiredKeys)
        assertEquals(state, back)
        assertFailsWith<IllegalArgumentException> { PairedDevices.restore(listOf(tablet), setOf(keyB)) }
        // A revoked row restored without its retired key still retires it.
        val revokedRow = PairedDevice.restore(phoneId, "Phone", keyA, revoked = true)
        assertTrue(keyA in PairedDevices.restore(listOf(revokedRow), emptySet()).retiredKeys)
        assertTrue(PairedDevices.restore(listOf(revokedRow), emptySet()).active.isEmpty())
    }

    @Test fun rename_and_revoke_touch_only_their_device() {
        val two = devices.pair(tablet).rename(tabletId, "Desk tablet").revoke(phoneId)
        assertEquals("Desk tablet", two.find(tabletId)?.name)
        assertEquals(listOf(tabletId), two.active.map { it.id })
        assertEquals(2, two.all.size)
        assertEquals(setOf(keyA), two.retiredKeys)
    }

    @Test fun device_values_are_validated() {
        assertFailsWith<IllegalArgumentException> { PairedDevice.create("x", "Phone", keyA) }
        assertFailsWith<IllegalArgumentException> { PairedDevice.create(phoneId, " ", keyA) }
        assertFailsWith<IllegalArgumentException> { PairedDevice.create(phoneId, "Phone", "zz") }
    }

    @Test fun advertisement_round_trips_and_rejects_malformed_txt() {
        val ad = LanAdvertisement.forSelf(phoneId, 4242)
        assertEquals(ad, LanAdvertisement.fromTxt(ad.toTxt()))
        val base = ad.toTxt()
        val bad = listOf(
            base + ("v" to "2"), base - "v", base + ("id" to "a b"), base + ("id" to "short"),
            base + ("port" to "0"), base + ("port" to "65536"), base + ("port" to "-1"),
            base + ("port" to "99999999999"), base + ("port" to "4a"), base - "port", base + ("port" to ""),
            base + ("port" to "04242"), base + ("port" to "٤٢٤٢"),
        )
        bad.forEach { assertNull(LanAdvertisement.fromTxt(it), it.toString()) }
        assertFailsWith<IllegalArgumentException> { LanAdvertisement.forSelf(phoneId, 70_000) }
        assertFailsWith<IllegalArgumentException> { LanAdvertisement.forSelf("SN-12345678", 80) }
    }

    @Test fun only_active_paired_ads_are_dialed_and_only_when_enabled() {
        val both = devices.pair(tablet)
        val seen = listOf(txt(phoneId, 1111), txt(tabletId, 2222), txt(strangerId, 3333), mapOf("v" to "1"))
        assertEquals(listOf(phoneId, tabletId), LanPeers.dialable(true, both, seen).map { it.deviceId })
        // Revoking the tablet must remove it even though it stays in `all`.
        val tabletRevoked = both.revoke(tabletId)
        assertEquals(listOf(phoneId), LanPeers.dialable(true, tabletRevoked, seen).map { it.deviceId })
        assertTrue(LanPeers.dialable(false, both, seen).isEmpty())
        assertTrue(LanPeers.dialable(true, both.revoke(phoneId).revoke(tabletId), seen).isEmpty())
    }

    @Test fun manual_endpoint_accepts_only_private_ipv4_literals() {
        assertEquals("192.168.1.5" to 4242, LanEndpoint.parse(" 192.168.1.5:4242 ")?.let { it.host to it.port })
        assertEquals("10.0.0.7" to 80, LanEndpoint.parse("10.0.0.7:80")?.let { it.host to it.port })
        assertEquals("172.16.0.1" to 9, LanEndpoint.parse("172.16.0.1:9")?.let { it.host to it.port })
        assertEquals("169.254.1.1" to 9, LanEndpoint.parse("169.254.1.1:9")?.let { it.host to it.port })
        listOf(
            "8.8.8.8:80", "172.32.0.1:80", "192.169.1.1:80", "example.com:80", "192.168.1.5", "192.168.1.5:0",
            "192.168.1.5:65536", "192.168.1.256:80", "[fe80::1]:80", "192.168.1:80", "192.168.1.5:80:1", "",
            "192.168.1.5:", "192.168.1.5:٤٢", "192.168.1.٥:80", "192.168.01.5:80", "192.168.1.5:080",
            "169.254.169.254:80", "169.254.169.1:80", "169.254.170.2:80", "192.168.1.0:80", "192.168.1.255:80",
            "10.0.0.255:80", "255.255.255.255:80",
        ).forEach { assertNull(LanEndpoint.parse(it), it) }
    }

    @Test fun manual_dial_is_gated_like_discovery() {
        assertEquals(80, LanPeers.manualDial(true, devices, "192.168.1.5:80")?.port)
        assertNull(LanPeers.manualDial(false, devices, "192.168.1.5:80"))
        assertNull(LanPeers.manualDial(true, PairedDevices.EMPTY, "192.168.1.5:80"))
        assertNull(LanPeers.manualDial(true, devices.revoke(phoneId), "192.168.1.5:80"))
        assertNull(LanPeers.manualDial(true, devices, "8.8.8.8:80"))
    }

    @Test fun hardware_shaped_ids_are_rejected() {
        listOf("aa:bb:cc:dd:ee:ff", "SN1234567890", "R5CT1234567", "353879234252633", "A".repeat(32), "1".repeat(31), "1".repeat(33))
            .forEach { assertFailsWith<IllegalArgumentException>(it) { PairedDevice.create(it, "Phone", keyA) } }
    }

    @Test fun names_are_cleaned_of_spoofing_characters() {
        assertEquals("Phone", PairedDevice.cleanName("‮Pho\u0000ne⁦ "))
        assertEquals("AB", PairedDevice.cleanName("A؜B"))                   // Arabic letter mark
        assertEquals("AB", PairedDevice.cleanName("A B "))             // line/paragraph separators
        assertEquals("AB", PairedDevice.cleanName("A​‌‍⁠﻿B")) // zero-width
        assertEquals("AB", PairedDevice.cleanName("A󠁁B"))             // tag character (U+E0041)
        assertEquals("AB", PairedDevice.cleanName("A\uD800B\uDC00"))             // unpaired surrogates
        assertEquals("A😀", PairedDevice.cleanName("A😀"))   // a real emoji survives
        // 63 units then an emoji pair: the cap must not leave half a pair.
        val long = "x".repeat(63) + "😀"
        assertEquals("x".repeat(63), PairedDevice.cleanName(long))
        // Trim happens after the cap.
        assertEquals("x".repeat(63), PairedDevice.cleanName("x".repeat(63) + " tail"))
        assertEquals("Desk", devices.rename(phoneId, "‮ Desk\n").find(phoneId)?.name)
        assertEquals("Phone", devices.rename(phoneId, "‮").find(phoneId)?.name) // blank result keeps the old name
        assertFailsWith<IllegalArgumentException> { PairedDevice.create(phoneId, "‮evil", keyA) }
        assertFailsWith<IllegalArgumentException> { PairedDevice.create(phoneId, "‮", keyA) }
    }

    @Test fun find_matches_exactly_and_misses_cleanly() {
        val two = devices.pair(tablet)
        assertEquals(tablet, two.find(tabletId))
        assertEquals(phone, two.find(phoneId))
        assertNull(two.find(strangerId))
        assertNull(two.find(phoneId.dropLast(1)))
    }
}
