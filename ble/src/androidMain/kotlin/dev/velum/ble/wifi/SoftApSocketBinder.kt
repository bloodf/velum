package dev.velum.ble.wifi

import android.net.Network
import java.net.InetAddress
import java.net.Socket

/** SoftAP per-socket helpers. Prefer these over bindProcessToNetwork. */
object SoftApSocketBinder {
    fun openClientSocket(network: Network): Socket =
        network.socketFactory.createSocket()

    fun openClientSocket(network: Network, host: String, port: Int): Socket =
        network.socketFactory.createSocket(InetAddress.getByName(host), port)

    fun bindSocket(network: Network, socket: Socket) {
        network.bindSocket(socket)
    }
}
