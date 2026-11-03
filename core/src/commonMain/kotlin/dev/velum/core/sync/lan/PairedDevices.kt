package dev.velum.core.sync.lan

/**
 * A device the user paired with (#65). [publicKeyHex] is the static key the pairing ceremony authenticated;
 * it is what a later handshake must prove, so the id alone never grants anything.
 */
data class PairedDevice(
    val id: String,
    val name: String,
    val publicKeyHex: String,
    val revoked: Boolean = false,
) {
    init {
        require(id.matches(DEVICE_ID)) { "Invalid device id" }
        require(name == cleanName(name) && name.isNotBlank()) { "Invalid device name" }
        require(publicKeyHex.matches(KEY_HEX)) { "Invalid public key" }
    }

    companion object {
        /**
         * A random 128-bit identifier generated per install, as 32 lowercase hex digits. The shape deliberately
         * excludes hardware serials, MAC addresses and the like, which must never identify a device on a LAN.
         */
        val DEVICE_ID = Regex("[0-9a-f]{32}")
        val KEY_HEX = Regex("[0-9a-f]{64}")

        /** Drops control and bidi-override characters (they can spoof a name in a list), trims and caps at 64. */
        fun cleanName(raw: String): String =
            raw.filterNot { it.isISOControl() || it in BIDI }.trim().take(64)

        private val BIDI = setOf('‎', '‏') + ('‪'..'‮') + ('⁦'..'⁩')
    }
}

/**
 * Immutable list of paired devices. Revoking keeps the row so a revoked key can never be re-trusted: a key is
 * unique across all rows, revoked ones included, so coming back means a new pairing ceremony with a new key.
 */
data class PairedDevices(val all: List<PairedDevice> = emptyList()) {
    init {
        require(all.map { it.id }.toSet().size == all.size) { "Duplicate device id" }
        require(all.map { it.publicKeyHex }.toSet().size == all.size) { "Duplicate public key" }
    }

    val active: List<PairedDevice> get() = all.filterNot { it.revoked }

    /**
     * Result of a completed pairing ceremony. Rejects a key already held by any other row, and a revoked row
     * coming back with its own key; a known id with a fresh key replaces its entry.
     */
    fun pair(device: PairedDevice): PairedDevices {
        require(!device.revoked) { "Pair an active device" }
        val sameId = find(device.id)
        require(sameId == null || !sameId.revoked || sameId.publicKeyHex != device.publicKeyHex) {
            "A revoked key cannot be trusted again"
        }
        require(all.none { it.id != device.id && it.publicKeyHex == device.publicKeyHex }) {
            "Public key already belongs to another device"
        }
        return PairedDevices(all.filterNot { it.id == device.id } + device)
    }

    fun rename(id: String, name: String): PairedDevices =
        PairedDevices(all.map { if (it.id == id) it.copy(name = PairedDevice.cleanName(name)) else it })

    fun revoke(id: String): PairedDevices =
        PairedDevices(all.map { if (it.id == id) it.copy(revoked = true) else it })

    fun find(id: String): PairedDevice? = all.firstOrNull { it.id == id }
}
