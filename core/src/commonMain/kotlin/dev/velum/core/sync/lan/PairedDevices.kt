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
        require(name.isNotBlank() && name.length <= 64) { "Invalid device name" }
        require(publicKeyHex.matches(KEY_HEX)) { "Invalid public key" }
    }

    companion object {
        val DEVICE_ID = Regex("[A-Za-z0-9_-]{8,64}")
        val KEY_HEX = Regex("[0-9a-f]{64}")
    }
}

/** Immutable list of paired devices. Revoking keeps the row so a revoked key can never be re-trusted by accident. */
data class PairedDevices(val all: List<PairedDevice> = emptyList()) {
    val active: List<PairedDevice> get() = all.filterNot { it.revoked }

    /** Result of a completed pairing ceremony: adds the device, or replaces a prior entry for the same id. */
    fun pair(device: PairedDevice): PairedDevices =
        PairedDevices(all.filterNot { it.id == device.id } + device)

    fun rename(id: String, name: String): PairedDevices =
        PairedDevices(all.map { if (it.id == id) it.copy(name = name) else it })

    fun revoke(id: String): PairedDevices =
        PairedDevices(all.map { if (it.id == id) it.copy(revoked = true) else it })

    fun find(id: String): PairedDevice? = all.firstOrNull { it.id == id }
}
