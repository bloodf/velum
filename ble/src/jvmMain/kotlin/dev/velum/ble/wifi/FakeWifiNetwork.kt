package dev.velum.ble.wifi

import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

class FakeWifiSoftApJoiner(
    val bindAddress: String = "127.0.0.1",
) : WifiSoftApJoiner {
    private val joined = AtomicBoolean(false)
    val isJoined: Boolean get() = joined.get()

    override suspend fun join(ssid: String, password: String, ssidIsPattern: Boolean) {
        require(ssid.isNotEmpty()) { "ssid required" }
        require(password.isNotEmpty()) { "password required" }
        joined.set(true)
    }

    override suspend fun leave() {
        joined.set(false)
    }
}

class FakeWifiNetwork(
    val bindAddress: String = "127.0.0.1",
    val port: Int = WifiTransferServer.DEFAULT_PORT,
) {
    val softAp: FakeWifiSoftApJoiner = FakeWifiSoftApJoiner(bindAddress)
    val server: JvmWifiTransferServer = JvmWifiTransferServer()

    fun session(frameCipher: WifiFrameCipher): WifiFastTransferSession =
        WifiFastTransferSession(softAp, server, frameCipher)
}

class FakeWifiClient(
    private val host: String = "127.0.0.1",
) {
    fun connectSendSealedSession(
        port: Int,
        preamble: ByteArray,
        frameCipher: WifiFrameCipher,
        plaintextChunks: List<ByteArray>,
    ) {
        var seq = 0L
        val sealed = ArrayList<ByteArray>(plaintextChunks.size + 1)
        for (chunk in plaintextChunks) {
            sealed += frameCipher.seal(seq, chunk)
            seq += 1
        }
        sealed += frameCipher.seal(seq, ByteArray(0))
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), 5_000)
            socket.soTimeout = 15_000
            val out = socket.getOutputStream()
            out.write(preamble)
            out.flush()
            for (frame in sealed) writeFrame(out, frame)
            out.flush()
            Thread.sleep(50)
        }
    }

    fun connectSendRawPreamble(port: Int, preamble: ByteArray) {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), 5_000)
            val out = socket.getOutputStream()
            out.write(preamble)
            out.flush()
            Thread.sleep(50)
        }
    }

    private fun writeFrame(out: OutputStream, body: ByteArray) {
        out.write(WifiTransferFraming.encodeFrame(body))
    }
}
