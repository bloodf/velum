package dev.velum.ble.wifi

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.PatternMatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * SoftAP join — Plaud APK parity (`WifiNetworkSpecifier` + WPA2).
 *
 * Does **not** call [ConnectivityManager.bindProcessToNetwork]. Exposes
 * [boundNetwork] / [softApHostAddress] so the WebSocket :8081 server can bind
 * sockets to the SoftAP [Network] via [Network.bindSocket] / [Network.socketFactory].
 */
class AndroidWifiSoftApJoiner(
    private val context: Context,
) : WifiSoftApJoiner {
    private var connectivityManager: ConnectivityManager? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var boundNetwork: Network? = null
    private var linkProperties: LinkProperties? = null

    fun boundNetwork(): Network? = boundNetwork

    /** IPv4 host on the SoftAP network for binding the WS listener, if known. */
    fun softApHostAddress(): String? {
        val props = linkProperties ?: return null
        return props.linkAddresses
            .asSequence()
            .map { it.address }
            .firstOrNull { !it.isLoopbackAddress && it.hostAddress?.contains(':') != true }
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
                    linkProperties = cm.getLinkProperties(network)
                    if (cont.isActive) cont.resume(Unit)
                }

                override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
                    if (network == boundNetwork) {
                        linkProperties = properties
                    }
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
        linkProperties = null
        connectivityManager = null
    }
}
