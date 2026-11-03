package dev.velum.core.sync.lan

/** What a peer announces over mDNS/NSD: a hint to dial, never proof of identity. */
data class LanAdvertisement(val deviceId: String, val port: Int) {
    fun toTxt(): Map<String, String> = mapOf("v" to VERSION.toString(), "id" to deviceId, "port" to port.toString())

    companion object {
        const val SERVICE_TYPE = "_velum-sync._tcp"
        const val VERSION = 1

        /** Strict parse of a TXT map from the network; null for anything malformed or from another version. */
        fun fromTxt(txt: Map<String, String>): LanAdvertisement? {
            if (txt["v"] != VERSION.toString()) return null
            val id = txt["id"]?.takeIf { it.matches(PairedDevice.DEVICE_ID) } ?: return null
            val port = txt["port"]?.takeIf { it.length <= 5 && it.all(Char::isDigit) }?.toInt() ?: return null
            if (port !in 1..65535) return null
            return LanAdvertisement(id, port)
        }
    }
}

/** A manually entered peer address, for networks that block mDNS (guest/corporate Wi-Fi). */
data class LanEndpoint(val host: String, val port: Int) {
    companion object {
        /** `a.b.c.d:port` with a private or link-local IPv4 literal; anything else (names, public IPs, IPv6) is null. */
        fun parse(input: String): LanEndpoint? {
            val parts = input.trim().split(":")
            if (parts.size != 2) return null
            val octets = parts[0].split(".")
            if (octets.size != 4 || octets.any { it.isEmpty() || it.length > 3 || !it.all(Char::isDigit) }) return null
            val n = octets.map { it.toInt() }
            if (n.any { it > 255 }) return null
            val private = n[0] == 10 ||
                (n[0] == 172 && n[1] in 16..31) ||
                (n[0] == 192 && n[1] == 168) ||
                (n[0] == 169 && n[1] == 254)
            if (!private) return null
            val port = parts[1].takeIf { it.length <= 5 && it.all(Char::isDigit) }?.toInt() ?: return null
            return if (port in 1..65535) LanEndpoint(octets.joinToString(".") { it.toInt().toString() }, port) else null
        }
    }
}

object LanPeers {
    /**
     * Only advertisements from an active paired device are worth dialing; unknown and revoked ids are dropped
     * before any connection, and nothing is returned while LAN sync is off.
     */
    fun dialable(enabled: Boolean, devices: PairedDevices, seen: List<LanAdvertisement>): List<LanAdvertisement> {
        if (!LanSyncGate.mayListenOrAdvertise(enabled, devices)) return emptyList()
        val active = devices.active.map { it.id }.toSet()
        return seen.filter { it.deviceId in active }
    }
}
