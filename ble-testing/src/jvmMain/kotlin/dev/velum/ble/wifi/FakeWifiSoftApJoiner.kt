package dev.velum.ble.wifi

/**
 * JVM SoftAP stand-in: no real Wi-Fi; [join]/leave] are no-ops so WS :8081
 * tests can run against [JavaWebsocketTransferServer] on loopback.
 */
class FakeWifiSoftApJoiner : WifiSoftApJoiner {
    @Volatile
    var joined: Boolean = false
        private set

    /** Successful joins so far: with `!joined` it proves a join then a leave happened, not that none ran. */
    @Volatile
    var joinCount: Int = 0
        private set

    override suspend fun join(ssid: String, password: String, ssidIsPattern: Boolean) {
        require(ssid.isNotBlank()) { "ssid required" }
        require(password.isNotBlank()) { "password required" }
        joinCount++
        joined = true
    }

    override suspend fun leave() {
        joined = false
    }
}
