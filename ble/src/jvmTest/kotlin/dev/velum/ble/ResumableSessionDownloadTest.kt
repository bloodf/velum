package dev.velum.ble

import dev.velum.ble.client.CrcPolicy
import dev.velum.ble.client.CrcStatus
import dev.velum.ble.client.PartialTransferStore
import dev.velum.ble.client.PlaudDeviceClient
import dev.velum.ble.client.ResumableSessionDownload
import dev.velum.ble.client.TransferIntegrityException
import dev.velum.ble.crypto.createSessionCipher
import dev.velum.ble.crypto.rsaKeyPairToPem
import dev.velum.ble.fake.FakeBleTransport
import dev.velum.ble.fake.FakePlaudPeripheral
import dev.velum.ble.fake.FakePlaudPeripheral.StopCrc
import dev.velum.ble.fake.FakePlaudPeripheral.StopReply
import dev.velum.ble.fake.TransferFaults
import dev.velum.protocol.Crc16Ccitt
import dev.velum.protocol.DeviceIdentityKeys
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import java.io.IOException
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.milliseconds

/**
 * #94 software side of #6: resume and CRC against the simulated peripheral (no hardware). Synthetic
 * keys and pseudo-random bytes; a real Note Pro's resume semantics and CRC algorithm stay unverified.
 */
class ResumableSessionDownloadTest {
    private val pems = rsaKeyPairToPem()
    private val keys = DeviceIdentityKeys(
        serialNumber = "881SYNTH",
        serialSignature = ByteArray(256) { (it % 251).toByte() },
        publicKeyPem = pems.first,
        privateKeyPem = pems.second,
        bindToken = "synthetic",
    )
    private val cipher = createSessionCipher()

    private class MemoryStore : PartialTransferStore {
        var bytes = ByteArray(0)
        override fun write(data: ByteArray) { bytes += data }
        override fun length() = bytes.size.toLong()
        override fun truncate(length: Long) { bytes = bytes.copyOf(length.toInt()) }
        override fun read(offset: Long, length: Int) = bytes.copyOfRange(offset.toInt(), offset.toInt() + length)
    }

    /** Grows without per-write copies (the 2-hour file). */
    private class BigStore(capacity: Int) : PartialTransferStore {
        private val buf = ByteArray(capacity)
        private var len = 0
        override fun write(data: ByteArray) { data.copyInto(buf, len); len += data.size }
        override fun length() = len.toLong()
        override fun truncate(length: Long) { len = length.toInt() }
        override fun read(offset: Long, length: Int) = buf.copyOfRange(offset.toInt(), offset.toInt() + length)
        fun snapshot() = buf.copyOf(len)
    }

    private class Rig(val peripheral: FakePlaudPeripheral, val ble: FakeBleTransport) {
        var connects = 0
    }

    private fun rig(
        file: ByteArray,
        faults: TransferFaults = TransferFaults(),
        stopCrc: StopCrc = StopCrc.Ccitt,
        chunk: Int = 200,
        stopReply: StopReply = StopReply.Cmd29,
    ): Rig {
        val peripheral = FakePlaudPeripheral(cipher, keys, file, chunkSize = chunk, faults = faults, stopCrc = stopCrc, stopReply = stopReply)
        return Rig(peripheral, FakeBleTransport(peripheral))
    }

    private fun downloader(rig: Rig, policy: CrcPolicy) = ResumableSessionDownload(
        connect = {
            rig.connects++
            rig.ble.connect("fake-note-pro")
            PlaudDeviceClient(rig.ble, cipher, keys, responseTimeout = 5.seconds)
        },
        crcPolicy = policy,
    )

    @Test fun two_hour_synthetic_session_downloads_byte_exact_with_matching_crc() = runTest(timeout = 5.minutes) {
        // 2 h at 32 kbit/s (4,000 B/s) ≈ the Note Pro's Opus rate; 240-byte chunks ≈ 120k packets.
        val file = Random(2).nextBytes(2 * 60 * 60 * 4_000)
        val rig = rig(file, chunk = 240)
        val store = BigStore(file.size)
        val result = downloader(rig, CrcPolicy.Enforce).download(SESSION, file.size.toLong(), store)
        assertEquals(CrcStatus.Verified, result.crc)
        assertEquals(1, result.attempts)
        assertEquals(file.size.toLong(), result.sizeBytes)
        assertTrue(store.snapshot().contentEquals(file), "downloaded bytes differ from the fixture")
        assertEquals(Crc16Ccitt.compute(file), Crc16Ccitt.compute(store.snapshot()))
    }

    @Test fun disconnects_at_several_offsets_resume_from_the_last_received_byte() = runTest {
        val file = Random(3).nextBytes(50_000)
        val rig = rig(file, TransferFaults(disconnectAt = setOf(7_000, 21_000, 44_000)))
        val store = MemoryStore()
        val result = downloader(rig, CrcPolicy.Enforce).download(SESSION, file.size.toLong(), store)
        assertContentEquals(file, store.bytes)
        assertEquals(CrcStatus.Verified, result.crc)
        assertEquals(4, result.attempts)
        // 200-byte chunks: each resume starts exactly at the first byte not yet received.
        assertEquals(listOf(0L, 7_000L, 21_000L, 44_000L), rig.peripheral.syncStartOffsets)
    }

    @Test fun integrity_failures_resume_without_duplicate_or_missing_bytes() = runTest {
        val file = Random(4).nextBytes(30_000)
        // The fake streams ahead of the client by up to its buffers, so each fault gets its own peripheral.
        val cases = listOf(
            TransferFaults(garbleAt = setOf(3_000)) to 3_000L,
            TransferFaults(dropAt = setOf(12_000)) to 12_000L,
            TransferFaults(lostNotificationAt = setOf(18_000)) to 18_000L,
            TransferFaults(overlapAt = setOf(25_000)) to 25_000L,
        )
        for ((faults, resumedAt) in cases) {
            val rig = rig(file, faults)
            val store = MemoryStore()
            val result = downloader(rig, CrcPolicy.Enforce).download(SESSION, file.size.toLong(), store)
            assertContentEquals(file, store.bytes)
            assertEquals(CrcStatus.Verified, result.crc)
            assertEquals(listOf(0L, resumedAt), rig.peripheral.syncStartOffsets)
        }
    }

    @Test fun simulated_corruption_is_never_verified_and_a_retry_gives_the_exact_file() = runTest {
        val file = Random(5).nextBytes(20_000)
        val rig = rig(file, TransferFaults(corruptAt = setOf(8_000)))
        val store = MemoryStore()
        val result = downloader(rig, CrcPolicy.Enforce).download(SESSION, file.size.toLong(), store)
        assertEquals(2, result.attempts, "the mismatching attempt must not be accepted")
        assertEquals(listOf(0L, 0L), rig.peripheral.syncStartOffsets, "a CRC mismatch restarts from 0")
        assertContentEquals(file, store.bytes)
        assertEquals(CrcStatus.Verified, result.crc)
    }

    @Test fun real_device_crc_mismatch_is_reported_and_kept_but_never_verified() = runTest {
        val file = Random(6).nextBytes(20_000)
        val rig = rig(file, TransferFaults(corruptAt = setOf(8_000)))
        val store = MemoryStore()
        val result = downloader(rig, CrcPolicy.ReportOnly).download(SESSION, file.size.toLong(), store)
        val mismatch = assertIs<CrcStatus.Mismatch>(result.crc)
        assertEquals(Crc16Ccitt.compute(file), mismatch.device)
        assertEquals(Crc16Ccitt.compute(store.bytes), mismatch.computed)
        assertEquals(1, result.attempts)
        val received = file.copyOf().also { it[8_000] = (it[8_000].toInt() xor 0x5A).toByte() }
        assertContentEquals(received, store.bytes, "the file is kept as received")
    }

    @Test fun device_without_crc_is_not_provided_not_verified() = runTest {
        val file = Random(8).nextBytes(5_000)
        val rig = rig(file, stopCrc = StopCrc.NotProvided)
        val result = downloader(rig, CrcPolicy.ReportOnly).download(SESSION, file.size.toLong(), MemoryStore())
        assertEquals(CrcStatus.NotProvided, result.crc)
    }

    @Test fun failed_reconnects_use_retries_and_then_the_transfer_completes() = runTest {
        val file = Random(9).nextBytes(5_000)
        val rig = rig(file)
        var calls = 0
        val download = ResumableSessionDownload(
            connect = {
                when (++calls) {
                    1 -> throw IOException("synthetic connect failure")
                    2 -> withTimeout(1.milliseconds) { delay(1.seconds) } // an internal timeout, not a cancellation
                    3 -> Unit // link not up: unlock fails inside the attempt
                    else -> rig.ble.connect("fake-note-pro")
                }
                PlaudDeviceClient(rig.ble, cipher, keys, responseTimeout = 5.seconds)
            },
            crcPolicy = CrcPolicy.Enforce,
        )
        val store = MemoryStore()
        val result = download.download(SESSION, file.size.toLong(), store)
        assertContentEquals(file, store.bytes)
        assertEquals(4, result.attempts)
    }

    @Test fun a_hanging_connect_is_bounded_and_uses_a_retry() = runTest(timeout = 30.seconds) {
        val file = Random(17).nextBytes(5_000)
        val rig = rig(file)
        var calls = 0
        val download = ResumableSessionDownload(
            connect = {
                if (++calls == 1) awaitCancellation()
                rig.ble.connect("fake-note-pro")
                PlaudDeviceClient(rig.ble, cipher, keys, responseTimeout = 5.seconds)
            },
            crcPolicy = CrcPolicy.Enforce,
            connectTimeout = 10.seconds,
        )
        val store = MemoryStore()
        val result = download.download(SESSION, file.size.toLong(), store)
        assertContentEquals(file, store.bytes)
        assertEquals(2, result.attempts)
    }

    @Test fun a_hanging_write_during_unlock_is_bounded_and_uses_a_retry() = runTest(timeout = 30.seconds) {
        val file = Random(18).nextBytes(5_000)
        val rig = rig(file)
        var hang = true
        rig.ble.beforeWrite = {
            if (hang) {
                hang = false
                awaitCancellation()
            }
        }
        val download = ResumableSessionDownload(
            connect = {
                rig.ble.connect("fake-note-pro")
                PlaudDeviceClient(rig.ble, cipher, keys, responseTimeout = 5.seconds)
            },
            crcPolicy = CrcPolicy.Enforce,
            connectTimeout = 10.seconds,
        )
        val store = MemoryStore()
        val result = download.download(SESSION, file.size.toLong(), store)
        assertContentEquals(file, store.bytes)
        assertEquals(2, result.attempts)
    }

    private fun storeHolding(bytes: ByteArray) = MemoryStore().apply { write(bytes) }

    @Test fun device_ignoring_the_offset_catches_up_past_one_mib() = runTest {
        val file = Random(19).nextBytes(1_200_000)
        val rig = rig(file, TransferFaults(ignoreStartOffset = true))
        val store = BigStore(file.size).apply { write(file.copyOf(1_100_000)) }
        val result = downloader(rig, CrcPolicy.Enforce).download(SESSION, file.size.toLong(), store)
        assertContentEquals(file, store.snapshot())
        assertEquals(listOf(1_100_000L), rig.peripheral.syncStartOffsets)
        assertEquals(1, result.attempts)
    }

    @Test fun device_refusing_a_resume_restarts_from_zero() = runTest {
        val file = Random(20).nextBytes(5_000)
        val rig = rig(file, TransferFaults(refuseNonZeroStart = true))
        val store = storeHolding(file.copyOf(2_000))
        val result = downloader(rig, CrcPolicy.Enforce).download(SESSION, file.size.toLong(), store)
        assertContentEquals(file, store.bytes)
        assertEquals(listOf(2_000L, 0L), rig.peripheral.syncStartOffsets)
        assertEquals(CrcStatus.Verified, result.crc)
    }

    @Test fun unaligned_restart_on_resume_restarts_from_zero() = runTest {
        val file = Random(21).nextBytes(5_000)
        val rig = rig(file, TransferFaults(ignoreStartOffset = true))
        val store = storeHolding(file.copyOf(1_050)) // chunks of 200 overlap this partially
        val result = downloader(rig, CrcPolicy.Enforce).download(SESSION, file.size.toLong(), store)
        assertContentEquals(file, store.bytes)
        assertEquals(listOf(1_050L, 0L), rig.peripheral.syncStartOffsets)
        assertEquals(CrcStatus.Verified, result.crc)
    }

    @Test fun relative_offsets_on_a_resume_never_join_wrong_bytes() = runTest {
        // More remains than is stored, so a relative offset reaches the expected one with the wrong bytes.
        val file = Random(25).nextBytes(5_000)
        val rig = rig(file, TransferFaults(relativeOffsets = true))
        val store = storeHolding(file.copyOf(2_000))
        val result = downloader(rig, CrcPolicy.ReportOnly).download(SESSION, file.size.toLong(), store)
        assertContentEquals(file, store.bytes)
        assertEquals(listOf(2_000L, 0L), rig.peripheral.syncStartOffsets)
        assertEquals(CrcStatus.Verified, result.crc)
    }

    @Test fun relative_offsets_with_little_left_restart_from_zero() = runTest {
        val file = Random(26).nextBytes(5_000)
        val rig = rig(file, TransferFaults(relativeOffsets = true))
        val store = storeHolding(file.copyOf(4_000))
        downloader(rig, CrcPolicy.Enforce).download(SESSION, file.size.toLong(), store)
        assertContentEquals(file, store.bytes)
        assertEquals(listOf(4_000L, 0L), rig.peripheral.syncStartOffsets)
    }

    @Test fun bad_stored_bytes_restart_from_zero() = runTest {
        val file = Random(27).nextBytes(5_000)
        val rig = rig(file, TransferFaults(ignoreStartOffset = true))
        val store = storeHolding(ByteArray(2_000) { 0x11 })
        // ReportOnly: Enforce would also recover through the CRC, which real devices don't have.
        val result = downloader(rig, CrcPolicy.ReportOnly).download(SESSION, file.size.toLong(), store)
        assertContentEquals(file, store.bytes)
        assertEquals(listOf(2_000L, 0L), rig.peripheral.syncStartOffsets)
        assertEquals(CrcStatus.Verified, result.crc)
    }

    @Test fun another_sessions_leftover_chunk_does_not_wipe_a_good_partial() = runTest {
        val file = Random(28).nextBytes(5_000)
        val rig = rig(file, TransferFaults(ignoreStartOffset = true, strayAt = setOf(0L)))
        val store = storeHolding(file.copyOf(2_000))
        downloader(rig, CrcPolicy.Enforce).download(SESSION, file.size.toLong(), store)
        assertContentEquals(file, store.bytes)
        assertEquals(listOf(2_000L), rig.peripheral.syncStartOffsets)
    }

    @Test fun stored_bytes_are_compared_once_per_attempt() = runTest {
        val file = Random(29).nextBytes(5_000)
        val rig = rig(file, TransferFaults(ignoreStartOffset = true), StopCrc.NotProvided)
        val base = storeHolding(file.copyOf(2_000))
        var reads = 0
        val store = object : PartialTransferStore by base {
            override fun read(offset: Long, length: Int): ByteArray {
                reads++
                return base.read(offset, length)
            }
        }
        downloader(rig, CrcPolicy.Enforce).download(SESSION, file.size.toLong(), store)
        assertContentEquals(file, base.bytes)
        assertEquals(1, reads)
    }

    @Test fun a_device_that_cannot_resume_on_a_flaky_link_stops_after_a_few_restarts() = runTest {
        // Each pass from 0 makes progress then loses the link; the resume is refused, so the partial is thrown
        // away and the new bytes reset the no-progress count. Without a restart cap this never ends (#212).
        val file = Random(31).nextBytes(5_000)
        val rig = rig(file, TransferFaults(refuseNonZeroStart = true, disconnectEveryPassAt = 1_000))
        val base = MemoryStore()
        var wipes = 0
        val store = object : PartialTransferStore by base {
            override fun truncate(length: Long) {
                if (length == 0L && base.length() > 0 && ++wipes > 20) throw AssertionError("restarted $wipes times, no cap")
                base.truncate(length)
            }
        }
        val e = assertFailsWith<TransferIntegrityException> {
            downloader(rig, CrcPolicy.Enforce).download(SESSION, file.size.toLong(), store)
        }
        assertTrue(e.message.orEmpty().contains("restart"), e.message)
        assertTrue(wipes <= 3, "wipes=$wipes")
        assertTrue(base.length() > 0, "the partial is kept when giving up")
    }

    @Test fun a_permanent_gap_with_lost_links_still_terminates() = runTest {
        // Every pass from 0 loses the link at 1,000, the resume then runs to the gap at 10,000 and adds bytes
        // each cycle, and the chunk at 10,000 never arrives. Resumed progress must not reset the restart cap,
        // or this loops until cancelled.
        val file = Random(46).nextBytes(20_000)
        val rig = rig(file, TransferFaults(disconnectEveryPassAt = 1_000, dropAlwaysAt = setOf(10_000)))
        val base = MemoryStore()
        var wipes = 0
        val store = object : PartialTransferStore by base {
            override fun truncate(length: Long) {
                if (length == 0L && base.length() > 0 && ++wipes > 20) throw AssertionError("restarted $wipes times, no end")
                base.truncate(length)
            }
        }
        val e = assertFailsWith<TransferIntegrityException> {
            downloader(rig, CrcPolicy.Enforce).download(SESSION, file.size.toLong(), store)
        }
        assertTrue(e.message.orEmpty().contains("restart"), e.message)
        assertEquals(3, wipes)
    }

    @Test fun device_silent_on_a_resume_restarts_from_zero_after_the_retries() = runTest {
        val file = Random(30).nextBytes(5_000)
        val rig = rig(file, TransferFaults(silentOnNonZeroStart = true))
        val store = storeHolding(file.copyOf(2_000))
        val result = downloader(rig, CrcPolicy.Enforce).download(SESSION, file.size.toLong(), store)
        assertContentEquals(file, store.bytes)
        assertEquals(List(5) { 2_000L } + 0L, rig.peripheral.syncStartOffsets)
        assertEquals(CrcStatus.Verified, result.crc)
    }

    @Test fun malformed_reply_on_a_resume_restarts_from_zero_after_the_retries() = runTest {
        val file = Random(31).nextBytes(5_000)
        val rig = rig(file, TransferFaults(malformedReplyOnNonZeroStart = true))
        val store = storeHolding(file.copyOf(2_000))
        downloader(rig, CrcPolicy.Enforce).download(SESSION, file.size.toLong(), store)
        assertContentEquals(file, store.bytes)
        assertEquals(List(5) { 2_000L } + 0L, rig.peripheral.syncStartOffsets)
    }

    @Test fun one_failed_connect_among_the_retries_keeps_the_partial() = runTest {
        val file = Random(32).nextBytes(5_000)
        val rig = rig(file, TransferFaults(silentOnNonZeroStart = true))
        var calls = 0
        val download = ResumableSessionDownload(
            connect = {
                if (++calls == 1) throw IOException("synthetic")
                rig.ble.connect("fake-note-pro")
                PlaudDeviceClient(rig.ble, cipher, keys, responseTimeout = 5.seconds)
            },
            crcPolicy = CrcPolicy.Enforce,
        )
        val store = storeHolding(file.copyOf(2_000))
        assertFailsWith<TransferIntegrityException> { download.download(SESSION, file.size.toLong(), store) }
        assertContentEquals(file.copyOf(2_000), store.bytes)
        assertEquals(List(4) { 2_000L }, rig.peripheral.syncStartOffsets)
    }

    @Test fun full_disk_on_every_attempt_keeps_the_partial_and_reports_the_error() = runTest {
        val file = Random(33).nextBytes(5_000)
        val rig = rig(file)
        val base = storeHolding(file.copyOf(2_000))
        val store = object : PartialTransferStore by base {
            override fun write(data: ByteArray) = throw IOException("synthetic: no space left on device")
        }
        val failure = assertFailsWith<TransferIntegrityException> {
            downloader(rig, CrcPolicy.Enforce).download(SESSION, file.size.toLong(), store)
        }
        assertEquals("synthetic: no space left on device", failure.cause?.message)
        assertContentEquals(file.copyOf(2_000), base.bytes)
        assertEquals(List(5) { 2_000L }, rig.peripheral.syncStartOffsets)
    }

    @Test fun failed_read_of_the_partial_on_every_attempt_keeps_the_partial() = runTest {
        val file = Random(34).nextBytes(5_000)
        val rig = rig(file, TransferFaults(ignoreStartOffset = true))
        val base = storeHolding(file.copyOf(2_000))
        val store = object : PartialTransferStore by base {
            override fun read(offset: Long, length: Int) = throw IOException("synthetic read failure")
        }
        val failure = assertFailsWith<TransferIntegrityException> {
            downloader(rig, CrcPolicy.Enforce).download(SESSION, file.size.toLong(), store)
        }
        assertEquals("synthetic read failure", failure.cause?.message)
        assertContentEquals(file.copyOf(2_000), base.bytes)
        assertEquals(List(5) { 2_000L }, rig.peripheral.syncStartOffsets)
    }

    @Test fun lost_link_right_after_a_resume_keeps_the_partial() = runTest {
        val file = Random(22).nextBytes(5_000)
        val rig = rig(file, TransferFaults(disconnectAt = setOf(2_000)))
        val store = storeHolding(file.copyOf(2_000))
        downloader(rig, CrcPolicy.Enforce).download(SESSION, file.size.toLong(), store)
        assertContentEquals(file, store.bytes)
        assertEquals(listOf(2_000L, 2_000L), rig.peripheral.syncStartOffsets)
    }

    @Test fun failed_connect_keeps_the_partial() = runTest {
        val file = Random(23).nextBytes(5_000)
        val rig = rig(file)
        var calls = 0
        val download = ResumableSessionDownload(
            connect = {
                if (++calls == 1) throw IOException("synthetic")
                rig.ble.connect("fake-note-pro")
                PlaudDeviceClient(rig.ble, cipher, keys, responseTimeout = 5.seconds)
            },
            crcPolicy = CrcPolicy.Enforce,
        )
        val store = storeHolding(file.copyOf(2_000))
        download.download(SESSION, file.size.toLong(), store)
        assertContentEquals(file, store.bytes)
        assertEquals(listOf(2_000L), rig.peripheral.syncStartOffsets)
    }

    @Test fun integrity_failure_after_new_bytes_keeps_the_partial() = runTest {
        val file = Random(24).nextBytes(5_000)
        val rig = rig(file, TransferFaults(dropAt = setOf(3_000)))
        val store = storeHolding(file.copyOf(2_000))
        downloader(rig, CrcPolicy.Enforce).download(SESSION, file.size.toLong(), store)
        assertContentEquals(file, store.bytes)
        assertEquals(listOf(2_000L, 3_000L), rig.peripheral.syncStartOffsets)
    }

    @Test fun attempts_without_progress_back_off_and_then_give_up() = runTest {
        var calls = 0
        val download = ResumableSessionDownload(connect = { calls++; throw IOException("synthetic") }, crcPolicy = CrcPolicy.Enforce)
        assertFailsWith<TransferIntegrityException> { download.download(SESSION, 5_000L, MemoryStore()) }
        assertEquals(5, calls)
        assertEquals(1_000L + 2_000 + 4_000 + 8_000, testScheduler.currentTime, "delays between the 5 attempts")
    }

    @Test fun progressing_attempts_do_not_use_up_the_retry_budget() = runTest {
        val file = Random(10).nextBytes(20_000)
        val offsets = (1..12).map { it * 1_000L }.toSet()
        val rig = rig(file, TransferFaults(disconnectAt = offsets))
        val store = MemoryStore()
        val result = downloader(rig, CrcPolicy.Enforce).download(SESSION, file.size.toLong(), store)
        assertContentEquals(file, store.bytes)
        assertEquals(13, result.attempts)
    }

    @Test fun missing_stop_reply_keeps_the_complete_file_without_verifying_it() = runTest {
        val file = Random(11).nextBytes(5_000)
        val rig = rig(file, stopReply = StopReply.Silent)
        val store = MemoryStore()
        val result = downloader(rig, CrcPolicy.Enforce).download(SESSION, file.size.toLong(), store)
        assertContentEquals(file, store.bytes)
        assertEquals(CrcStatus.NotProvided, result.crc)
        assertEquals(1, result.attempts)
    }

    @Test fun a_store_already_holding_every_byte_does_not_connect() = runTest {
        val file = Random(12).nextBytes(5_000)
        val rig = rig(file)
        val store = MemoryStore().apply { write(file) }
        val result = downloader(rig, CrcPolicy.Enforce).download(SESSION, file.size.toLong(), store)
        assertEquals(0, rig.connects)
        assertEquals(CrcStatus.NotProvided, result.crc)
        assertContentEquals(file, store.bytes)
    }

    @Test fun caller_cancellation_is_not_retried() = runTest {
        val file = Random(13).nextBytes(5_000)
        val rig = rig(file)
        val store = object : PartialTransferStore by MemoryStore() {
            override fun write(data: ByteArray) = throw CancellationException("synthetic caller cancellation")
        }
        val job = launch { downloader(rig, CrcPolicy.Enforce).download(SESSION, file.size.toLong(), store) }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(1, rig.connects)
        assertFalse(job.isActive)
    }

    @Test fun a_store_larger_than_the_session_restarts_from_zero() = runTest {
        val file = Random(14).nextBytes(5_000)
        val rig = rig(file)
        val store = MemoryStore().apply { write(ByteArray(file.size + 1)) }
        val result = downloader(rig, CrcPolicy.Enforce).download(SESSION, file.size.toLong(), store)
        assertContentEquals(file, store.bytes)
        assertEquals(listOf(0L), rig.peripheral.syncStartOffsets)
        assertEquals(CrcStatus.Verified, result.crc)
        assertEquals(1, result.attempts)
    }

    @Test fun enforced_crc_mismatch_gives_up_after_three_complete_transfers() = runTest {
        val file = Random(15).nextBytes(5_000)
        val rig = rig(file, stopCrc = StopCrc.Wrong)
        assertFailsWith<TransferIntegrityException> {
            downloader(rig, CrcPolicy.Enforce).download(SESSION, file.size.toLong(), MemoryStore())
        }
        assertEquals(3, rig.connects)
    }

    @Test fun lost_links_between_mismatches_do_not_defeat_the_crc_cap() = runTest(timeout = 30.seconds) {
        val file = Random(16).nextBytes(10_000)
        // Lost link, mismatch, lost link, ...: each lost link stores new bytes, which resets the no-progress count.
        val rig = rig(file, TransferFaults(disconnectEveryPassAt = 1_000L), StopCrc.Wrong)
        assertFailsWith<TransferIntegrityException> {
            downloader(rig, CrcPolicy.Enforce).download(SESSION, file.size.toLong(), MemoryStore())
        }
        assertEquals(listOf(0L, 1_000L, 0L, 1_000L, 0L, 1_000L), rig.peripheral.syncStartOffsets)
    }

    private companion object { const val SESSION = 1_700_000_000L }
}
