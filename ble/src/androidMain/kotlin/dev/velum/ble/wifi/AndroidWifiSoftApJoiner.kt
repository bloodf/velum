package dev.velum.ble.wifi

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.PatternMatcher
import java.net.Inet4Address
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * SoftAP join (WifiNetworkSpecifier + WPA2). Never calls bindProcessToNetwork.
 * Per-socket SoftAP routing uses [boundNetwork] with Network.socketFactory /
 * Network.bindSocket from [AndroidWifiTransferServer] / [SoftApSocketBinder].
 */
class AndroidWifiSoftApJoiner(
    private val context: Context,
) : WifiSoftApJoiner {
    private var connectivityManager: ConnectivityManager? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var boundNetwork: Network? = null

    fun boundNetwork(): Network? = boundNetwork

    fun softApIpv4Host(): String? {
        val network = boundNetwork ?: return null
        val cm = connectivityManager ?: return null
        val props = cm.getLinkProperties(network) ?: return null
        return props.linkAddresses
            .asSequence()
            .map { it.address }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
    }

    override suspend fun join(ssid: String, password: String, ssidIsPattern: Boolean) {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "SoftAP join requires Android 10+"
        }
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivityManager = cm
        val specifier = WifiNetworkSpecifier.Builder().apply {
            if (ssidIsPattern) {
                setSsidPattern(PatternMatcher(ssid, PatternMatcher.PATTERN_PREFIX))
            } else {
                setSsid(ssid)
            }
            setWpa2Passphrase(password)
        }.build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .setNetworkSpecifier(specifier)
            .build()

        suspendCancellableCoroutine { cont ->
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    boundNetwork = network
                    if (cont.isActive) cont.resume(Unit)
                }

                override fun onUnavailable() {
                    if (cont.isActive) {
                        cont.resumeWithException(
                            IllegalStateException("SoftAP network unavailable for ssid=$ssid"),
                        )
                    }
                }
            }
            callback = cb
            cm.requestNetwork(request, cb)
            cont.invokeOnCancellation { leaveInternal() }
        }
    }

    override suspend fun leave() {
        leaveInternal()
    }

    private fun leaveInternal() {
        val cm = connectivityManager
        val cb = callback
        if (cm != null && cb != null) {
            runCatching { cm.unregisterNetworkCallback(cb) }
        }
        callback = null
        boundNetwork = null
        connectivityManager = null
    }
}
