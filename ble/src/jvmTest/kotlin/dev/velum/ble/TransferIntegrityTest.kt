package dev.velum.ble

import dev.velum.ble.client.PlaudDeviceClient
import dev.velum.ble.client.SessionByteSink
import dev.velum.ble.client.TransferIntegrityException
import dev.velum.ble.crypto.createSessionCipher
import dev.velum.ble.crypto.rsaKeyPairToPem
import dev.velum.ble.fake.FakeBleTransport
import dev.velum.ble.fake.FakePlaudPeripheral
import dev.velum.ble.fake.TransferFaults
import dev.velum.protocol.DeviceIdentityKeys
import java.io.ByteArrayOutputStream
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest

/** #94: a single transfer must fail fast on bytes it cannot trust instead of stalling or gapping. Synthetic keys/data. */
class TransferIntegrityTest {
    private val pems = rsaKeyPairToPem()
    private val keys = DeviceIdentityKeys(
        serialNumber = "881SYNTH",
        serialSignature = ByteArray(256) { (it % 251).toByte() },
        publicKeyPem = pems.first,
        privateKeyPem = pems.second,
        bindToken = "synthetic",
    )
    private val cipher = createSessionCipher()
    private val file = Random(7).nextBytes(10_000)

    private suspend fun clientFor(faults: TransferFaults): PlaudDeviceClient {
        val peripheral = FakePlaudPeripheral(cipher, keys, file, chunkSize = 200, faults = faults)
        val ble = FakeBleTransport(peripheral)
        ble.connect("fake-note-pro")
        return PlaudDeviceClient(ble, cipher, keys, responseTimeout = 5.seconds).also { it.unlock() }
    }

    private class Sink : SessionByteSink {
        val out = ByteArrayOutputStream()
        override fun write(data: ByteArray) = out.write(data)
    }

    @Test fun packet_failing_authentication_fails_the_transfer_instead_of_stalling() = runTest {
        // Before #94 the client skipped the packet; every later packet then failed its sequence check,
        // so the transfer hung until the response timeout and surfaced as a cancellation.
        val client = clientFor(TransferFaults(garbleAt = setOf(2_000)))
        assertFailsWith<TransferIntegrityException> { client.downloadSessionTo(1_700_000_000L, file.size.toLong(), Sink()) }
        client.close()
    }

    @Test fun missing_chunk_is_a_gap_not_a_shorter_file() = runTest {
        val client = clientFor(TransferFaults(dropAt = setOf(4_000)))
        assertFailsWith<TransferIntegrityException> { client.downloadSessionTo(1_700_000_000L, file.size.toLong(), Sink()) }
        client.close()
    }

    @Test fun repeated_chunk_is_an_overlap_not_duplicate_bytes() = runTest {
        val client = clientFor(TransferFaults(duplicateAt = setOf(6_000)))
        assertFailsWith<TransferIntegrityException> { client.downloadSessionTo(1_700_000_000L, file.size.toLong(), Sink()) }
        client.close()
    }

    @Test fun notification_lost_to_a_buffer_overflow_fails_instead_of_gapping() = runTest {
        val client = clientFor(TransferFaults(lostNotificationAt = setOf(8_000)))
        val sink = Sink()
        assertFailsWith<TransferIntegrityException> { client.downloadSessionTo(1_700_000_000L, file.size.toLong(), sink) }
        assertContentEquals(file.copyOf(8_000), sink.out.toByteArray(), "nothing after the lost packet is kept")
        client.close()
    }

    @Test fun clean_transfer_is_byte_exact() = runTest {
        val client = clientFor(TransferFaults())
        val sink = Sink()
        client.downloadSessionTo(1_700_000_000L, file.size.toLong(), sink)
        assertContentEquals(file, sink.out.toByteArray())
        client.close()
    }
}
