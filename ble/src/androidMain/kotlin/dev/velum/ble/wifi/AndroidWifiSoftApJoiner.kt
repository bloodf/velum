package dev.velum.ble.wifi

import android.content.Context
import android.net.ConnectivityManager
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
 * SoftAP join skeleton (APK uses WifiNetworkSpecifier + WPA2).
 *
 * Compiles on Android 10+; does not contact Plaud servers. Full transfer UX
 * remains blocked on owner hardware approval.
 */
class AndroidWifiSoftApJoiner(
    private val context: Context,
) : WifiSoftApJoiner {
    private var connectivityManager: ConnectivityManager? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var boundNetwork: Network? = null

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
                    cm.bindProcessToNetwork(network)
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
        cm?.bindProcessToNetwork(null)
        callback = null
        boundNetwork = null
        connectivityManager = null
    }
}
