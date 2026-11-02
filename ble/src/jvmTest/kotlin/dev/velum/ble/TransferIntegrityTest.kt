package dev.velum.ble

import dev.velum.ble.client.PlaudDeviceClient
import dev.velum.ble.client.SessionByteSink
import dev.velum.ble.client.TransferIntegrityException
import dev.velum.ble.crypto.createSessionCipher
import dev.velum.ble.crypto.rsaKeyPairToPem
import dev.velum.ble.fake.FakeBleTransport
import dev.velum.ble.gatt.GattWrite
import dev.velum.ble.fake.FakePlaudPeripheral
import dev.velum.ble.fake.TransferFaults
import dev.velum.protocol.Commands
import dev.velum.protocol.DeviceIdentityKeys
import java.io.ByteArrayOutputStream
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

/** #94: a transfer must fail fast on bytes it cannot trust, and tolerate what is harmless. Synthetic keys/data. */
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

    private var peripheral: FakePlaudPeripheral? = null

    private suspend fun clientFor(
        faults: TransferFaults,
        stopReply: FakePlaudPeripheral.StopReply = FakePlaudPeripheral.StopReply.Cmd29,
    ): PlaudDeviceClient {
        val fake = FakePlaudPeripheral(cipher, keys, file, chunkSize = 200, faults = faults, stopReply = stopReply)
        peripheral = fake
        val ble = FakeBleTransport(fake)
        ble.connect("fake-note-pro")
        return PlaudDeviceClient(ble, cipher, keys, responseTimeout = 5.seconds).also { it.unlock() }
    }

    private class Sink : SessionByteSink {
        val out = ByteArrayOutputStream()
        override fun write(data: ByteArray) = out.write(data)
    }

    private suspend fun assertFailsKeepingOnly(bytesKept: Int, faults: TransferFaults) {
        val client = clientFor(faults)
        val sink = Sink()
        assertFailsWith<TransferIntegrityException> { client.downloadSessionTo(SESSION, file.size.toLong(), sink) }
        assertContentEquals(file.copyOf(bytesKept), sink.out.toByteArray(), "nothing past the failure is kept")
        assertTrue(Commands.SYNC_FILE_STOP in peripheral!!.receivedCommands, "the device is asked to stop streaming")
        client.close()
    }

    @Test fun packet_failing_authentication_shows_up_as_a_gap_not_a_stall() = runTest {
        // Before #94 the client skipped the packet and required the exact next seq, so every later packet
        // was rejected too: the transfer hung until the response timeout and surfaced as a cancellation.
        val started = testScheduler.currentTime
        assertFailsKeepingOnly(2_000, TransferFaults(garbleAt = setOf(2_000)))
        assertTrue(testScheduler.currentTime - started < 5_000, "must fail before the response timeout")
    }

    @Test fun missing_chunk_is_a_gap_not_a_shorter_file() = runTest {
        assertFailsKeepingOnly(4_000, TransferFaults(dropAt = setOf(4_000)))
    }

    @Test fun notification_lost_to_a_buffer_overflow_is_a_gap_not_a_shorter_file() = runTest {
        assertFailsKeepingOnly(8_000, TransferFaults(lostNotificationAt = setOf(8_000)))
    }

    @Test fun partial_overlap_fails() = runTest {
        assertFailsKeepingOnly(6_000, TransferFaults(overlapAt = setOf(6_000)))
    }

    @Test fun chunk_overrunning_the_declared_size_is_not_written() = runTest {
        val client = clientFor(TransferFaults())
        val sink = Sink()
        // The device holds more than was declared: the chunk crossing the limit must not reach the sink.
        assertFailsWith<TransferIntegrityException> { client.downloadSessionTo(SESSION, 5_050L, sink) }
        assertContentEquals(file.copyOf(5_000), sink.out.toByteArray())
        client.close()
    }

    @Test fun repeated_chunks_replays_and_strays_are_skipped_without_duplicate_bytes() = runTest {
        val faults = TransferFaults(duplicateAt = setOf(1_000), replayAt = setOf(3_000), strayAt = setOf(5_000))
        val client = clientFor(faults)
        val sink = Sink()
        client.downloadSessionTo(SESSION, file.size.toLong(), sink)
        assertContentEquals(file, sink.out.toByteArray())
        client.close()
    }

    @Test fun complete_transfer_survives_a_missing_stop_reply() = runTest {
        val client = clientFor(TransferFaults(), FakePlaudPeripheral.StopReply.Silent)
        val sink = Sink()
        assertEquals(PlaudDeviceClient.NO_DEVICE_CRC, client.downloadSessionTo(SESSION, file.size.toLong(), sink))
        assertContentEquals(file, sink.out.toByteArray())
        client.close()
    }

    @Test fun stop_reply_30_is_accepted_and_30_is_never_sent() = runTest {
        val client = clientFor(TransferFaults(), FakePlaudPeripheral.StopReply.Cmd30)
        val started = testScheduler.currentTime
        assertEquals(PlaudDeviceClient.NO_DEVICE_CRC, client.downloadSessionTo(SESSION, file.size.toLong(), Sink()))
        assertEquals(started, testScheduler.currentTime, "reply 30 must end the wait, not the timeout")
        assertFalse(Commands.DELETE_FILE in peripheral!!.receivedCommands)
        client.close()
    }

    @Test fun missing_stop_reply_does_not_swallow_the_callers_cancellation() = runTest {
        val job = launch {
            val client = clientFor(TransferFaults(), FakePlaudPeripheral.StopReply.Silent)
            client.downloadSessionTo(SESSION, file.size.toLong(), Sink())
            error("cancellation must not be swallowed")
        }
        runCurrent()
        assertTrue(job.isActive, "waiting for the stop reply")
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
    }

    @Test fun stray_chunk_at_the_expected_offset_is_not_stored() = runTest {
        val client = clientFor(TransferFaults(strayAt = setOf(4_000)))
        val sink = Sink()
        client.downloadSessionTo(SESSION, file.size.toLong(), sink)
        assertContentEquals(file, sink.out.toByteArray())
        client.close()
    }

    @Test fun stray_end_marker_does_not_end_the_transfer() = runTest {
        val client = clientFor(TransferFaults(strayEndAt = setOf(4_000)))
        val sink = Sink()
        client.downloadSessionTo(SESSION, file.size.toLong(), sink)
        assertContentEquals(file, sink.out.toByteArray())
        client.close()
    }

    @Test fun resume_offset_survives_trailing_zero_trimming_of_the_command() = runTest {
        // With an unknown size the end offset is 0, which Framing trims off the SYNC_FILE_START params.
        val client = clientFor(TransferFaults())
        val sink = Sink()
        client.downloadSessionTo(SESSION, 0L, sink, startOffset = 2_000L)
        assertEquals(listOf(2_000L), peripheral!!.syncStartOffsets)
        assertContentEquals(file.copyOfRange(2_000, file.size), sink.out.toByteArray())
        client.close()
    }

    @Test fun replayed_command_reply_does_not_answer_a_later_command() = runTest {
        val client = clientFor(TransferFaults(replayStaleListReply = true))
        assertEquals(111L, client.listSessions(nowUnix = 111L).echoedNow)
        assertEquals(222L, client.listSessions(nowUnix = 222L).echoedNow, "the stale reply must be dropped")
        client.close()
    }

    @Test fun device_stuck_repeating_a_chunk_cannot_hang_the_download() = runTest(timeout = 60.seconds) {
        // Each repeat restarts the response timeout, so without a limit this never ends.
        val client = clientFor(TransferFaults(endlessRepeatAt = 2_000))
        val sink = Sink()
        assertFailsWith<TransferIntegrityException> { client.downloadSessionTo(SESSION, file.size.toLong(), sink) }
        assertContentEquals(file.copyOf(2_000), sink.out.toByteArray())
        client.close()
    }

    /** A real transport's write suspends, and takes an hour of test time once [hang] is set. */
    private class LinkTransport(private val delegate: BleTransport) : BleTransport by delegate {
        var hang = false

        override suspend fun writeRaw(write: GattWrite) {
            yield()
            if (hang) delay(1.hours)
            delegate.writeRaw(write)
        }
    }

    private suspend fun linkClient(
        faults: TransferFaults = TransferFaults(),
        stopReply: FakePlaudPeripheral.StopReply = FakePlaudPeripheral.StopReply.Cmd29,
    ): Pair<PlaudDeviceClient, LinkTransport> {
        val fake = FakePlaudPeripheral(cipher, keys, file, chunkSize = 200, faults = faults, stopReply = stopReply)
        peripheral = fake
        val link = LinkTransport(FakeBleTransport(fake))
        link.connect("fake-note-pro")
        return PlaudDeviceClient(link, cipher, keys, responseTimeout = 5.seconds).also { it.unlock() } to link
    }

    @Test fun abandoning_a_transfer_is_bounded_when_the_stop_write_hangs() = runTest(timeout = 30.seconds) {
        val (client, link) = linkClient(TransferFaults(dropAt = setOf(4_000)))
        val started = testScheduler.currentTime
        assertFailsWith<TransferIntegrityException> {
            client.downloadSessionTo(SESSION, file.size.toLong(), { if (it.isNotEmpty()) link.hang = true })
        }
        assertTrue(testScheduler.currentTime - started <= 5_000, "bounded by the response timeout")
        client.close()
    }

    @Test fun complete_transfer_survives_a_stop_write_that_hangs() = runTest(timeout = 30.seconds) {
        val (client, link) = linkClient()
        val sink = Sink()
        val started = testScheduler.currentTime
        val crc = client.downloadSessionTo(SESSION, file.size.toLong(), {
            sink.write(it)
            link.hang = true
        })
        assertTrue(testScheduler.currentTime - started <= 5_000, "bounded by the response timeout")
        assertEquals(PlaudDeviceClient.NO_DEVICE_CRC, crc)
        assertContentEquals(file, sink.out.toByteArray())
        client.close()
    }

    @Test fun cancelling_the_job_mid_transfer_still_sends_the_stop() = runTest {
        val (client, _) = linkClient()
        var job: Job? = null
        job = launch {
            client.downloadSessionTo(SESSION, file.size.toLong(), { job?.cancel() })
        }
        job.join()
        assertTrue(job.isCancelled)
        val commands = peripheral!!.receivedCommands
        assertEquals(1, commands.count { it == Commands.SYNC_FILE_START })
        assertEquals(1, commands.count { it == Commands.SYNC_FILE_STOP }, "stop must reach the device: $commands")
        client.close()
    }

    @Test fun enclosing_timeout_during_the_stop_reply_is_not_swallowed() = runTest {
        val client = clientFor(TransferFaults(), FakePlaudPeripheral.StopReply.Silent)
        var returned = false
        assertFailsWith<TimeoutCancellationException> {
            withTimeout(1.seconds) { // shorter than the client's 5 s response timeout
                client.downloadSessionTo(SESSION, file.size.toLong(), Sink())
                returned = true
            }
        }
        assertFalse(returned, "the enclosing timeout must not look like a missing reply")
        client.close()
    }

    @Test fun clean_transfer_is_byte_exact() = runTest {
        val client = clientFor(TransferFaults())
        val sink = Sink()
        client.downloadSessionTo(SESSION, file.size.toLong(), sink)
        assertContentEquals(file, sink.out.toByteArray())
        client.close()
    }

    private companion object { const val SESSION = 1_700_000_000L }
}
