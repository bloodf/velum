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

    @Synchronized
    fun boundNetwork(): Network? = boundNetwork

    /** IPv4 host on the SoftAP network for binding the WS listener, if known. */
    @Synchronized
    fun softApHostAddress(): String? {
        val props = linkProperties ?: return null
        return props.linkAddresses
            .asSequence()
            .map { it.address }
            .firstOrNull { !it.isLoopbackAddress && it.hostAddress?.contains(':') != true }
            ?.hostAddress
    }

    override suspend fun join(ssid: String, password: String, ssidIsPattern: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            error("SoftAP join requires Android 10+")
        }
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
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
                private var requestedNetwork: Network? = null

                override fun onAvailable(network: Network) {
                    synchronized(this@AndroidWifiSoftApJoiner) {
                        if (callback !== this || cont.isCancelled) return
                        requestedNetwork = network
                    }
                }

                override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
                    synchronized(this@AndroidWifiSoftApJoiner) {
                        if (callback !== this || cont.isCancelled || network != requestedNetwork) return
                        boundNetwork = network
                        linkProperties = properties
                        if (cont.isActive) cont.resume(Unit)
                    }
                }

                override fun onUnavailable() {
                    synchronized(this@AndroidWifiSoftApJoiner) {
                        if (callback !== this || cont.isCancelled) return
                        leaveInternal()
                        if (cont.isActive) {
                            cont.resumeWithException(
                                WifiTransferFailure("SoftAP network unavailable"),
                            )
                        }
                    }
                }
            }
            synchronized(this) {
                connectivityManager = cm
                callback = cb
                try {
                    cm.requestNetwork(request, cb)
                    cont.invokeOnCancellation {
                        synchronized(this) {
                            if (callback === cb) leaveInternal()
                        }
                    }
                } catch (failure: SecurityException) {
                    leaveInternal()
                    if (cont.isActive) cont.resumeWithException(failure)
                }
            }
        }
    }

    override suspend fun leave() {
        leaveInternal()
    }

    @Synchronized
    private fun leaveInternal() {
        val cm = connectivityManager
        val cb = callback
        callback = null
        boundNetwork = null
        linkProperties = null
        connectivityManager = null
        if (cm != null && cb != null) {
            runCatching { cm.unregisterNetworkCallback(cb) }
        }
    }
}
