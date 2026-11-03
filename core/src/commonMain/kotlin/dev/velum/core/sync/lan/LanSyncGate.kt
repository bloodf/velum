package dev.velum.core.sync.lan

enum class LanRejectReason { Disabled, UnknownDevice, Revoked, KeyMismatch }

sealed class LanAdmission {
    data class Accept(val device: PairedDevice) : LanAdmission()
    data class Reject(val reason: LanRejectReason) : LanAdmission()
}

/**
 * The single decision point for LAN sync (#66). Nothing in the app may open a listening socket, advertise
 * over mDNS or dial a peer unless these say so. Transport code is expected to call them, not re-derive.
 *
 * Rules: off by default (the user turns it on); no paired device means nothing to talk to, so nothing
 * listens; a peer is admitted only when the key its handshake authenticated equals the key pinned at
 * pairing. The id a peer claims is only a lookup hint.
 */
object LanSyncGate {
    /** [enabled] is the user's explicit opt-in setting, false until they turn it on. */
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
    ): LanAdmission {
        if (!enabled) return LanAdmission.Reject(LanRejectReason.Disabled)
        val device = devices.find(claimedId) ?: return LanAdmission.Reject(LanRejectReason.UnknownDevice)
        if (device.revoked) return LanAdmission.Reject(LanRejectReason.Revoked)
        if (device.publicKeyHex != authenticatedKeyHex) return LanAdmission.Reject(LanRejectReason.KeyMismatch)
        return LanAdmission.Accept(device)
    }
}
