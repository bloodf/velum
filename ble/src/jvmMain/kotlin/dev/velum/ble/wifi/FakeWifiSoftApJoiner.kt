package dev.velum.ble.wifi

/**
 * JVM SoftAP stand-in: no real Wi-Fi; [join]/leave] are no-ops so WS :8081
 * tests can run against [JavaWebsocketTransferServer] on loopback.
 */
class FakeWifiSoftApJoiner : WifiSoftApJoiner {
    @Volatile
    var joined: Boolean = false
        private set

    override suspend fun join(ssid: String, password: String, ssidIsPattern: Boolean) {
        require(ssid.isNotBlank()) { "ssid required" }
        require(password.isNotBlank()) { "password required" }
        joined = true
    }

    override suspend fun leave() {
        joined = false
    }
}
