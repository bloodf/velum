package dev.velum.core.sync.lan

import dev.velum.core.integrations.WebhookSigner

enum class LanRejectReason { Disabled, UnknownDevice, Revoked, KeyMismatch }

sealed class LanAdmission {
    /** Only [LanSyncGate.admit] issues this; transport code takes the device from here and nowhere else. */
    @ConsistentCopyVisibility
    data class Accept internal constructor(val device: PairedDevice) : LanAdmission()
    data class Reject(val reason: LanRejectReason) : LanAdmission()
}

/**
 * The single decision point for LAN sync (#66). Nothing in the app may open a listening socket, advertise
 * over mDNS or dial a peer unless these say so. Transport code is expected to call them, not re-derive.
 *
 * Rules: off by default (the user turns it on); no paired device means nothing to talk to, so nothing
 * listens; a peer is admitted only when the key its handshake authenticated equals the key pinned at
 * pairing. The id a peer claims is only a lookup hint.
 *
 * Revocation (#65) must also hold for sessions already open: a live session re-calls [admit] (or is closed) as
 * soon as the paired list changes, otherwise a revoked device keeps syncing until it reconnects.
 */
object LanSyncGate {
    /** [enabled] is the user's explicit opt-in. There is no default here: callers pass false until the user turns LAN sync on. */
    fun mayListenOrAdvertise(enabled: Boolean, devices: PairedDevices): Boolean =
        enabled && devices.active.isNotEmpty()

    /**
     * [authenticatedKeyHex] is the static key proven by the completed handshake, never a value the peer
     * merely sent. Callers must give the remote side one uniform failure whatever the [LanRejectReason],
     * which exists for local logs and tests only, so paired ids cannot be probed.
     */
    fun admit(
        enabled: Boolean,
        devices: PairedDevices,
        claimedId: String,
        authenticatedKeyHex: String,
    ): LanAdmission = admit(enabled, devices, claimedId, authenticatedKeyHex, WebhookSigner::constantTimeEquals)

    // [keysEqual] is injectable so a test can prove a compare always runs, even for unknown ids.
    internal fun admit(
        enabled: Boolean,
        devices: PairedDevices,
        claimedId: String,
        authenticatedKeyHex: String,
        keysEqual: (String, String) -> Boolean,
    ): LanAdmission {
        if (!enabled) return LanAdmission.Reject(LanRejectReason.Disabled)
        val device = devices.find(claimedId)
        // Compare against a dummy key for unknown ids so timing does not reveal which ids are paired.
        val keyMatches = keysEqual(device?.publicKeyHex ?: DUMMY_KEY, authenticatedKeyHex)
        if (device == null) return LanAdmission.Reject(LanRejectReason.UnknownDevice)
        if (device.revoked) return LanAdmission.Reject(LanRejectReason.Revoked)
        if (!keyMatches) return LanAdmission.Reject(LanRejectReason.KeyMismatch)
        return LanAdmission.Accept(device)
    }

    private val DUMMY_KEY = "0".repeat(64)
}
