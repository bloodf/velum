package dev.velum.core.sync.lan

/**
 * The advertised device id is provisional (owner decision 3 on #66, mDNS privacy): it is a stable identifier
 * visible to everyone on the LAN. Wiring [LanAdvertisement] into a real mDNS/NSD announcer needs an explicit
 * opt-in so it cannot happen by accident before that decision (rotating or salted ids?) is made.
 */
@RequiresOptIn("The advertised id is provisional until the owner decides how mDNS may identify a device", RequiresOptIn.Level.ERROR)
annotation class ProvisionalLanIdentity

private fun String.isAsciiDigits() = isNotEmpty() && all { it in '0'..'9' }

/** Decimal without leading zeros ("0" itself is fine); null otherwise, and null if it overflows. */
private fun String.strictInt(maxLength: Int): Int? =
    takeIf { it.length <= maxLength && it.isAsciiDigits() && (it == "0" || it[0] != '0') }?.toIntOrNull()

/** What a peer announces over mDNS/NSD: a hint to dial, never proof of identity. */
@ProvisionalLanIdentity
@ConsistentCopyVisibility
data class LanAdvertisement private constructor(val deviceId: String, val port: Int) {
    init {
        require(deviceId.matches(PairedDevice.DEVICE_ID)) { "Invalid device id" }
        require(port in 1..65535) { "Invalid port" }
    }

    fun toTxt(): Map<String, String> = mapOf("v" to VERSION.toString(), "id" to deviceId, "port" to port.toString())

    companion object {
        const val SERVICE_TYPE = "_velum-sync._tcp"
        const val VERSION = 1

        /** This device's own announcement. */
        fun forSelf(deviceId: String, port: Int) = LanAdvertisement(deviceId, port)

        /** Strict parse of a TXT map from the network; null for anything malformed or from another version. */
        internal fun fromTxt(txt: Map<String, String>): LanAdvertisement? {
            if (txt["v"] != VERSION.toString()) return null
            val id = txt["id"]?.takeIf { it.matches(PairedDevice.DEVICE_ID) } ?: return null
            val port = txt["port"]?.strictInt(5)?.takeIf { it in 1..65535 } ?: return null
            return LanAdvertisement(id, port)
        }
    }
}

/** A manually entered peer address, for networks that block mDNS (guest/corporate Wi-Fi). */
@ConsistentCopyVisibility
data class LanEndpoint private constructor(val host: String, val port: Int) {
    init {
        require(parseHost(host) != null) { "Host must be a private or link-local IPv4 literal" }
        require(port in 1..65535) { "Invalid port" }
    }

    companion object {
        /** `a.b.c.d:port`; anything that is not an acceptable private IPv4 literal plus port is null. */
        internal fun parse(input: String): LanEndpoint? {
            val parts = input.trim().split(":")
            if (parts.size != 2) return null
            val host = parseHost(parts[0]) ?: return null
            val port = parts[1].strictInt(5)?.takeIf { it in 1..65535 } ?: return null
            return LanEndpoint(host, port)
        }

        /**
         * Canonical dotted quad for an RFC 1918 or link-local address, else null. No leading zeros, no network
         * (.0) or broadcast (.255) addresses, and not the cloud/container metadata ranges 169.254.169.0/24 and
         * 169.254.170.0/24.
         */
        private fun parseHost(host: String): String? {
            val octets = host.split(".")
            if (octets.size != 4) return null
            val n = octets.map { it.strictInt(3)?.takeIf { v -> v <= 255 } ?: return null }
            val private = n[0] == 10 ||
                (n[0] == 172 && n[1] in 16..31) ||
                (n[0] == 192 && n[1] == 168) ||
                (n[0] == 169 && n[1] == 254)
            if (!private || n[3] == 0 || n[3] == 255) return null
            // Cloud/container metadata services: 169.254.169.0/24 (AWS, GCP) and 169.254.170.0/24 (ECS).
            if (n[0] == 169 && n[1] == 254 && n[2] in 169..170) return null
            return octets.joinToString(".")
        }
    }
}

object LanPeers {
    /**
     * Raw TXT records off the network go in; only advertisements from an active paired device are worth dialing; unknown and revoked ids are dropped
     * before any connection, and nothing is returned while LAN sync is off.
     */
    @OptIn(ProvisionalLanIdentity::class)
    fun dialable(enabled: Boolean, devices: PairedDevices, seenTxt: List<Map<String, String>>): List<LanAdvertisement> {
        if (!LanSyncGate.mayListenOrAdvertise(enabled, devices)) return emptyList()
        val active = devices.active.map { it.id }.toSet()
        return seenTxt.mapNotNull { LanAdvertisement.fromTxt(it) }.filter { it.deviceId in active }
    }

    /** A typed address may be dialed only under the same gate as discovery; the handshake still authenticates it. */
    fun manualDial(enabled: Boolean, devices: PairedDevices, typed: String): LanEndpoint? =
        if (LanSyncGate.mayListenOrAdvertise(enabled, devices)) LanEndpoint.parse(typed) else null
}
