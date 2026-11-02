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
import kotlinx.coroutines.test.runTest

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
    ): Rig {
        val peripheral = FakePlaudPeripheral(cipher, keys, file, chunkSize = chunk, faults = faults, stopCrc = stopCrc)
        return Rig(peripheral, FakeBleTransport(peripheral))
    }

    private fun downloader(rig: Rig, policy: CrcPolicy) = ResumableSessionDownload(
        connect = {
            rig.connects++
            rig.ble.connect("fake-note-pro")
            PlaudDeviceClient(rig.ble, cipher, keys, responseTimeout = 5.seconds).also { it.unlock() }
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
        // The fake emits a whole pass at once, so each fault gets its own peripheral.
        val cases = listOf(
            TransferFaults(garbleAt = setOf(3_000)) to 3_000L,
            TransferFaults(dropAt = setOf(12_000)) to 12_000L,
            // The first copy is stored; the second copy is the overlap, so the resume continues after it.
            TransferFaults(duplicateAt = setOf(25_000)) to 25_200L,
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
        assertEquals(file.size, store.bytes.size)
    }

    @Test fun device_without_crc_is_not_provided_not_verified() = runTest {
        val file = Random(8).nextBytes(5_000)
        val rig = rig(file, stopCrc = StopCrc.NotProvided)
        val result = downloader(rig, CrcPolicy.ReportOnly).download(SESSION, file.size.toLong(), MemoryStore())
        assertEquals(CrcStatus.NotProvided, result.crc)
    }

    @Test fun persistent_failure_gives_up_after_max_attempts() = runTest {
        val file = Random(9).nextBytes(5_000)
        // A fault per attempt at the same offset, more than the attempt budget.
        val rig = rig(file, TransferFaults(disconnectAt = (0L..5L).toSet()))
        assertFailsWith<TransferIntegrityException> {
            downloader(rig, CrcPolicy.Enforce).download(SESSION, file.size.toLong(), MemoryStore())
        }
        assertEquals(5, rig.connects)
    }

    private companion object { const val SESSION = 1_700_000_000L }
}
