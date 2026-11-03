package dev.velum.core.sync.lan

import dev.velum.core.integrations.WebhookSigner

/**
 * A device the user paired with (#65). [publicKeyHex] is the static key the pairing ceremony authenticated;
 * it is what a later handshake must prove, so the id alone never grants anything.
 *
 * The constructor and `copy` are private: a revoked device cannot be flipped back to active by copying it.
 * Use [create] for a fresh pairing. [restore] is internal to this module and only for the persistence layer.
 */
@ConsistentCopyVisibility
data class PairedDevice private constructor(
    val id: String,
    val name: String,
    val publicKeyHex: String,
    val revoked: Boolean,
) {
    init {
        require(id.matches(DEVICE_ID)) { "Invalid device id" }
        require(name == cleanName(name) && name.isNotBlank()) { "Invalid device name" }
        require(publicKeyHex.matches(KEY_HEX)) { "Invalid public key" }
    }

    internal fun withName(newName: String) = PairedDevice(id, cleanName(newName).ifBlank { name }, publicKeyHex, revoked)

    internal fun asRevoked() = PairedDevice(id, name, publicKeyHex, revoked = true)

    companion object {
        /**
         * A random 128-bit identifier generated per install, as 32 lowercase hex digits. The shape deliberately
         * excludes hardware serials, MAC addresses and the like, which must never identify a device on a LAN.
         */
        val DEVICE_ID = Regex("[0-9a-f]{32}")
        val KEY_HEX = Regex("[0-9a-f]{64}")

        /** A device from a completed pairing ceremony: always active. */
        fun create(id: String, name: String, publicKeyHex: String) = PairedDevice(id, name, publicKeyHex, false)

        /**
         * Persistence only: rebuilds a stored row, revocation included. Never call this to re-trust a device.
         * Stored state must live in SecureStore or other authenticated storage; a store anyone can edit could
         * clear revocations, so the persistence layer must also reject a retired-key set that shrank.
         */
        internal fun restore(id: String, name: String, publicKeyHex: String, revoked: Boolean) =
            PairedDevice(id, name, publicKeyHex, revoked)

        /**
         * Drops everything that can spoof or blank a name: control and format characters (bidi, zero-width, ALM,
         * tag characters, supplementary format such as U+1D173), variation selectors including U+E0100, filler
         * and blank glyphs (U+3164, U+FFA0, U+115F, U+1160, U+2800, U+034F, U+17B4, U+17B5), line/paragraph
         * separators and unpaired surrogates. Caps at 64 UTF-16 units without splitting a surrogate pair, then
         * trims. A name that ends up blank is rejected by [create] and ignored by rename.
         */
        fun cleanName(raw: String): String {
            val kept = StringBuilder()
            var i = 0
            while (i < raw.length) {
                val c = raw[i]
                val pair = c.isHighSurrogate() && i + 1 < raw.length && raw[i + 1].isLowSurrogate()
                when {
                    pair -> {
                        val cp = 0x10000 + ((c.code - 0xD800) shl 10) + (raw[i + 1].code - 0xDC00)
                        if (!isInvisibleSupplementary(cp)) kept.append(c).append(raw[i + 1])
                        i += 2
                    }
                    c.isSurrogate() -> i++
                    c.isISOControl() || c.category == CharCategory.FORMAT ||
                        c.category == CharCategory.LINE_SEPARATOR || c.category == CharCategory.PARAGRAPH_SEPARATOR ||
                        c.code in INVISIBLE_BMP -> i++
                    else -> { kept.append(c); i++ }
                }
            }
            var s = kept.toString().take(64)
            if (s.isNotEmpty() && s.last().isHighSurrogate()) s = s.dropLast(1)
            return s.trim()
        }

        private val INVISIBLE_BMP: Set<Int> =
            (0xFE00..0xFE0F).toSet() + (0x180B..0x180F) + setOf(0x3164, 0xFFA0, 0x115F, 0x1160, 0x2800, 0x034F, 0x17B4, 0x17B5)

        private fun isInvisibleSupplementary(cp: Int): Boolean =
            cp in 0x1D173..0x1D17A || cp in 0xE0000..0xE007F || cp in 0xE0100..0xE01EF ||
                cp in 0x13430..0x13440 || cp in 0x1BCA0..0x1BCA3
    }
}

/**
 * Immutable list of paired devices plus every key that was ever trusted and then revoked or replaced
 * ([retiredKeys]). A key is unique across all rows and never comes back from the retired set, so re-trusting a
 * device means a new pairing ceremony with a new key. The constructor and `copy` are private; persistence goes
 * through the internal [restore].
 */
@ConsistentCopyVisibility
data class PairedDevices private constructor(val all: List<PairedDevice>, val retiredKeys: Set<String>) {
    init {
        require(all.map { it.id }.toSet().size == all.size) { "Duplicate device id" }
        require(all.map { it.publicKeyHex }.toSet().size == all.size) { "Duplicate public key" }
        require(all.filter { it.revoked }.all { it.publicKeyHex in retiredKeys }) { "Revoked key not retired" }
        require(all.filterNot { it.revoked }.none { it.publicKeyHex in retiredKeys }) { "Active key is retired" }
    }

    val active: List<PairedDevice> get() = all.filterNot { it.revoked }

    /**
     * Result of a completed pairing ceremony. Rejects a key that is retired or held by any other row; a known id
     * with a fresh key replaces its entry and retires the key it had.
     */
    fun pair(device: PairedDevice): PairedDevices {
        require(!device.revoked) { "Pair an active device" }
        require(device.publicKeyHex !in retiredKeys) { "A retired key cannot be trusted again" }
        require(all.none { it.id != device.id && it.publicKeyHex == device.publicKeyHex }) {
            "Public key already belongs to another device"
        }
        val previous = find(device.id)
        val retired = if (previous != null && previous.publicKeyHex != device.publicKeyHex) {
            retiredKeys + previous.publicKeyHex
        } else {
            retiredKeys
        }
        return PairedDevices(all.filterNot { it.id == device.id } + device, retired)
    }

    fun rename(id: String, name: String): PairedDevices =
        PairedDevices(all.map { if (it.id == id) it.withName(name) else it }, retiredKeys)

    fun revoke(id: String): PairedDevices {
        val target = find(id) ?: return this
        return PairedDevices(all.map { if (it.id == id) it.asRevoked() else it }, retiredKeys + target.publicKeyHex)
    }

    /** Scans every row and compares ids in constant time, so lookup time does not reveal which ids are paired. */
    fun find(id: String): PairedDevice? {
        var found: PairedDevice? = null
        for (d in all) if (WebhookSigner.constantTimeEquals(d.id, id)) found = d
        return found
    }

    companion object {
        val EMPTY = PairedDevices(emptyList(), emptySet())

        /**
         * Persistence only: rebuilds stored state, validating that no retired key is active again. The state
         * must come from SecureStore or other authenticated storage, and the caller must reject a [retiredKeys]
         * that is smaller than the one it saved last: shrinking it would re-trust a revoked key.
         */
        internal fun restore(all: List<PairedDevice>, retiredKeys: Set<String>): PairedDevices =
            PairedDevices(all, retiredKeys + all.filter { it.revoked }.map { it.publicKeyHex })
    }
}
