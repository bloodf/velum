package dev.velum.app.di

import androidx.lifecycle.ViewModelStore
import dev.velum.app.i18n.DeviceUiCopy
import dev.velum.app.platform.PartialTransfer
import dev.velum.app.platform.PartialTransferFileStore
import dev.velum.app.ui.DeviceViewModel
import dev.velum.app.ui.DownloadedSessionAudio
import dev.velum.ble.BleTransport
import dev.velum.ble.client.CrcStatus
import dev.velum.ble.crypto.JcaFileDecryptor
import dev.velum.ble.crypto.createFileDecryptor
import dev.velum.ble.crypto.createSessionCipher
import dev.velum.ble.crypto.rsaKeyPairToPem
import dev.velum.core.model.ProcessingWarning
import dev.velum.ble.fake.FakeBleTransport
import dev.velum.ble.fake.FakePlaudPeripheral
import dev.velum.ble.fake.TransferFaults
import dev.velum.core.security.DeviceKeyImporter
import dev.velum.protocol.DeviceIdentityKeys
import dev.velum.protocol.PlaudFileCrypto
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

/**
 * #94 through the real [DeviceViewModel]: a partial left by an earlier process continues from its
 * checkpoint, and the transfer retries inside one run. Synthetic identity; says nothing about a real
 * Note Pro's resume support (#6).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceResumeRestartTest {
    private val pems = rsaKeyPairToPem()
    private val serial = "881FAKE1"
    private val keys = DeviceIdentityKeys(
        serialNumber = serial,
        serialSignature = ByteArray(256) { (it % 251).toByte() },
        publicKeyPem = pems.first,
        privateKeyPem = pems.second,
        bindToken = "synthetic-token",
    )
    private val session = 1_700_000_000L
    private val plaintext = Random(21).nextBytes(40_000)
    private val encrypted = (createFileDecryptor() as JcaFileDecryptor).encryptFile(pems.first, plaintext)
    private val partials: File = Files.createTempDirectory("velum-resume-synthetic").toFile()

    @AfterTest fun cleanup() { partials.deleteRecursively() }

    private class Importer(private val keys: DeviceIdentityKeys) : DeviceKeyImporter {
        override suspend fun hasKeys() = true
        override suspend fun loadKeys() = keys
        override suspend fun importKeys(serialNumber: String, serialSignatureBase64: String, bindToken: String, publicKeyPem: String, privateKeyPem: String) = error("Not used")
        override suspend fun clearKeys() = error("Not used")
    }

    private fun run(
        peripheral: FakePlaudPeripheral,
        persist: suspend (DownloadedSessionAudio) -> String = { audio ->
            val out = ByteArrayOutputStream()
            audio.writeDecryptedAudio { out.write(it) }
            saved = out.toByteArray()
            "rec-synthetic"
        },
        open: (Long, String?) -> PartialTransfer? = { id, sn -> PartialTransferFileStore.open(partials, id, sn) },
        await: Boolean = true,
        identity: DeviceIdentityKeys = keys,
        deletePartials: () -> Unit = { PartialTransferFileStore.deleteAll(partials) },
        wipeJoinTimeout: Duration = 15.seconds,
        transport: (FakePlaudPeripheral) -> BleTransport = { FakeBleTransport(it) },
    ): DeviceViewModel = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val vm = DeviceViewModel(
            transport(peripheral),
            Importer(identity),
            persistDownloadedSession = { persist(it) },
            localeTag = { "en" },
            openPartialTransfer = open,
            deletePartialTransfers = deletePartials,
            wipeJoinTimeout = wipeJoinTimeout,
        )
        owner.put("device", vm)
        running = vm
        vm.connect("fake-note-pro")
        withTimeout(20_000) { while (vm.sessionUi.value.sessions.isEmpty()) delay(5) }
        vm.downloadSession(session)
        if (await) awaitOutcome(vm)
        vm
    }

    private fun awaitOutcome(vm: DeviceViewModel) = runBlocking {
        withTimeout(60_000) {
            while (vm.sessionUi.value.lastDownloadedRecordingId == null && vm.sessionUi.value.statusMessage != DeviceUiCopy.downloadFailed("en")) delay(5)
        }
    }

    private var saved: ByteArray? = null

    @Volatile private var running: DeviceViewModel? = null
    private val owner = ViewModelStore()

    /**
     * Waits for a cancelled download to finish unwinding before removing Main. Some tests hold it in NonCancellable
     * work, and resuming it with Main gone is an uncaught exception that fails the next runTest in this JVM.
     */
    private fun tearDown() {
        try {
            owner.clear()
            runBlocking { withTimeout(30_000) { running?.awaitDownloadEnd() } }
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun peripheral(
        faults: TransferFaults = TransferFaults(),
        stopCrc: FakePlaudPeripheral.StopCrc = FakePlaudPeripheral.StopCrc.NotProvided,
        file: ByteArray = encrypted,
        identity: DeviceIdentityKeys = keys,
    ) = FakePlaudPeripheral(createSessionCipher(), identity, file, sessionId = session, requirePublicKey = false, chunkSize = 200, faults = faults, stopCrc = stopCrc)

    @Test fun restart_continues_from_the_checkpoint_and_clears_the_partial() {
        try {
            val checkpoint = 10_000
            val earlier = PartialTransferFileStore.open(partials, session, serial)
            earlier.write(encrypted.copyOf(checkpoint))
            assertEquals(checkpoint.toLong(), earlier.length()) // the earlier process dies without closing
            val fake = peripheral()
            val vm = run(fake)
            assertEquals("rec-synthetic", vm.sessionUi.value.lastDownloadedRecordingId)
            assertEquals(listOf(checkpoint.toLong()), fake.syncStartOffsets, "must not restart the whole download")
            assertContentEquals(plaintext, assertNotNull(saved))
            assertFalse(partials.list()!!.any { it.endsWith(".partial") || it.endsWith(".offset") }, "partial is removed once persisted")
        } finally { tearDown() }
    }

    @Test fun lost_link_during_a_download_resumes_in_the_same_run() {
        try {
            val fake = peripheral(TransferFaults(disconnectAt = setOf(15_000)))
            val vm = run(fake)
            assertEquals("rec-synthetic", vm.sessionUi.value.lastDownloadedRecordingId)
            assertEquals(listOf(0L, 15_000L), fake.syncStartOffsets)
            assertContentEquals(plaintext, assertNotNull(saved))
            assertEquals(1, vm.sessionUi.value.sessions.size, "session list still works on the reconnected client")
        } finally { tearDown() }
    }

    @Test fun a_matching_device_crc_is_reported_as_verified() {
        try {
            val vm = run(peripheral(stopCrc = FakePlaudPeripheral.StopCrc.Ccitt))
            assertEquals(CrcStatus.Verified, vm.sessionUi.value.lastDownloadCrc)
            assertContentEquals(plaintext, assertNotNull(saved))
        } finally { tearDown() }
    }

    @Test fun a_device_with_no_crc_is_reported_as_not_provided() {
        try {
            val vm = run(peripheral())
            assertEquals(CrcStatus.NotProvided, vm.sessionUi.value.lastDownloadCrc)
        } finally { tearDown() }
    }

    @Test fun real_device_crc_mismatch_keeps_the_recording_and_says_so() {
        try {
            val vm = run(peripheral(stopCrc = FakePlaudPeripheral.StopCrc.Wrong))
            assertIs<CrcStatus.Mismatch>(vm.sessionUi.value.lastDownloadCrc)
            assertEquals("rec-synthetic", vm.sessionUi.value.lastDownloadedRecordingId)
            assertEquals(DeviceUiCopy.downloadedSessionCrcMismatch("en", session), vm.sessionUi.value.statusMessage)
            assertContentEquals(plaintext, assertNotNull(saved))
        } finally { tearDown() }
    }

    @Test fun crc_mismatch_is_stored_on_the_recording_and_survives_a_failed_save() {
        try {
            var failing = true
            val warnings = mutableListOf<String?>()
            val fake = peripheral(stopCrc = FakePlaudPeripheral.StopCrc.Wrong)
            val vm = run(fake, persist = { audio ->
                warnings += audio.processingWarning
                val out = ByteArrayOutputStream()
                audio.writeDecryptedAudio {
                    check(!failing) { "synthetic full disk" }
                    out.write(it)
                }
                saved = out.toByteArray()
                "rec-synthetic"
            })
            assertEquals(DeviceUiCopy.downloadFailed("en"), vm.sessionUi.value.statusMessage)
            assertTrue(partials.list()!!.any { it.endsWith(".crcmismatch") }, "the verdict is kept with the partial")
            failing = false
            vm.downloadSession(session)
            awaitOutcome(vm)
            assertEquals(listOf(0L), fake.syncStartOffsets, "nothing is transferred again")
            // The retry connects to nothing, so only the remembered mark can still say it.
            assertEquals(listOf<String?>(ProcessingWarning.DEVICE_CRC_MISMATCH, ProcessingWarning.DEVICE_CRC_MISMATCH), warnings)
            assertEquals(DeviceUiCopy.downloadedSessionCrcMismatch("en", session), vm.sessionUi.value.statusMessage)
            assertFalse(partials.list()!!.any { it.endsWith(".crcmismatch") }, "mark removed with the partial")
        } finally { tearDown() }
    }

    @Test fun a_failing_mark_write_is_not_fatal_and_the_warning_is_still_stored() {
        try {
            val warnings = mutableListOf<String?>()
            run(
                peripheral(stopCrc = FakePlaudPeripheral.StopCrc.Wrong),
                persist = { audio ->
                    warnings += audio.processingWarning
                    val out = ByteArrayOutputStream(); audio.writeDecryptedAudio { out.write(it) }; saved = out.toByteArray(); "rec-synthetic"
                },
                open = { id, sn ->
                    val store = PartialTransferFileStore.open(partials, id, sn)
                    object : PartialTransfer by store {
                        override fun setCrcMismatch(mismatch: Boolean) = throw IOException("synthetic: cannot write the mark")
                    }
                },
            )
            assertEquals(listOf<String?>(ProcessingWarning.DEVICE_CRC_MISMATCH), warnings)
        } finally { tearDown() }
    }

    @Test fun a_verified_transfer_stores_no_warning() {
        try {
            val warnings = mutableListOf<String?>()
            run(peripheral(stopCrc = FakePlaudPeripheral.StopCrc.Ccitt), persist = { audio ->
                warnings += audio.processingWarning
                val out = ByteArrayOutputStream(); audio.writeDecryptedAudio { out.write(it) }; saved = out.toByteArray(); "rec-synthetic"
            })
            assertEquals(listOf<String?>(null), warnings)
        } finally { tearDown() }
    }

    @Test fun without_a_durable_store_a_failed_download_can_be_retried_on_the_same_client() {
        try {
            var failing = true
            val vm = run(
                peripheral(),
                persist = { audio ->
                    check(!failing) { "synthetic save failure" }
                    val out = ByteArrayOutputStream()
                    audio.writeDecryptedAudio { out.write(it) }
                    saved = out.toByteArray()
                    "rec-synthetic"
                },
                open = { _, _ -> null },
            )
            assertEquals(DeviceUiCopy.downloadFailed("en"), vm.sessionUi.value.statusMessage)
            failing = false
            vm.downloadSession(session)
            awaitOutcome(vm)
            assertEquals("rec-synthetic", vm.sessionUi.value.lastDownloadedRecordingId)
            assertContentEquals(plaintext, assertNotNull(saved))
        } finally { tearDown() }
    }

    @Test fun failed_save_keeps_the_complete_partial_and_a_retry_needs_no_new_transfer() {
        try {
            var failing = true
            val fake = peripheral()
            val vm = run(fake, persist = { audio ->
                val out = ByteArrayOutputStream()
                audio.writeDecryptedAudio {
                    check(!failing) { "synthetic full disk" }
                    out.write(it)
                }
                saved = out.toByteArray()
                "rec-synthetic"
            })
            assertEquals(DeviceUiCopy.downloadFailed("en"), vm.sessionUi.value.statusMessage)
            assertTrue(partials.list()!!.any { it.endsWith(".partial") }, "the transfer is kept")
            failing = false
            vm.downloadSession(session)
            awaitOutcome(vm)
            assertEquals("rec-synthetic", vm.sessionUi.value.lastDownloadedRecordingId)
            assertContentEquals(plaintext, assertNotNull(saved))
            assertEquals(listOf(0L), fake.syncStartOffsets, "nothing is transferred again")
        } finally { tearDown() }
    }

    /** Lets the VM's connection collector see Connected before connect() returns, as a real Main dispatcher does. */
    private fun settled(fake: FakeBleTransport): BleTransport = object : BleTransport by fake {
        override suspend fun connect(deviceId: String) {
            fake.connect(deviceId)
            yield()
        }
    }

    @Test fun a_failed_download_after_a_reconnect_keeps_the_device_serial_for_the_retry() {
        try {
            // Keys without a serial: the advertised one is the only dedup/resume key. The mid-transfer reconnect
            // passes through Idle, which used to forget it, so the retry opened its partial with no serial (#212).
            val noSerial = keys.copy(serialNumber = "")
            val opened = mutableListOf<String?>()
            var failing = true
            val vm = run(
                peripheral(TransferFaults(disconnectAt = setOf(15_000)), identity = noSerial),
                identity = noSerial,
                transport = { p -> val fake = FakeBleTransport(p); settled(fake) },
                open = { id, sn -> opened += sn; PartialTransferFileStore.open(partials, id, sn) },
                persist = { audio ->
                    val out = ByteArrayOutputStream()
                    audio.writeDecryptedAudio { check(!failing) { "synthetic full disk" }; out.write(it) }
                    saved = out.toByteArray()
                    "rec-synthetic"
                },
            )
            assertEquals(DeviceUiCopy.downloadFailed("en"), vm.sessionUi.value.statusMessage)
            failing = false
            vm.downloadSession(session)
            awaitOutcome(vm)
            assertEquals(listOf<String?>("881FAKE1", "881FAKE1"), opened)
        } finally { tearDown() }
    }

    /** Counts connect() calls and records any made while [unwinding] is set. */
    private class ConnectProbe(private val fake: FakeBleTransport) : BleTransport by fake {
        @Volatile var unwinding = false
        val connects = java.util.concurrent.atomic.AtomicInteger()
        val connectsWhileUnwinding = java.util.concurrent.atomic.AtomicInteger()
        override suspend fun connect(deviceId: String) {
            connects.incrementAndGet()
            if (unwinding) connectsWhileUnwinding.incrementAndGet()
            fake.connect(deviceId)
            yield()
        }
    }

    @Test fun connect_waits_for_a_cancelled_download_that_is_still_unwinding() {
        try {
            val saving = CompletableDeferred<Unit>()
            var probe: ConnectProbe? = null
            val vm = run(
                peripheral(),
                transport = { p -> ConnectProbe(FakeBleTransport(p)).also { probe = it } },
                persist = { _ ->
                    probe!!.unwinding = true
                    saving.complete(Unit)
                    // A BLE write or save that ignores cancellation, then a failed save.
                    withContext(NonCancellable) { delay(600) }
                    probe!!.unwinding = false
                    throw IOException("synthetic full disk")
                },
                await = false,
            )
            runBlocking {
                withTimeout(20_000) { saving.await() }
                vm.cancelDownload()
                vm.connect("fake-note-pro")
                delay(1_500)
            }
            assertEquals(0, probe!!.connectsWhileUnwinding.get(), "connect must wait for the cancelled download to finish")
            assertEquals(2, probe!!.connects.get(), "and then connect")
        } finally { tearDown() }
    }

    @Test fun a_queued_download_does_not_block_connect_forever_nor_get_replaced_mid_transfer() {
        try {
            val saving = CompletableDeferred<Unit>()
            var probe: ConnectProbe? = null
            val persists = java.util.concurrent.atomic.AtomicInteger()
            val vm = run(
                peripheral(),
                transport = { p -> ConnectProbe(FakeBleTransport(p)).also { probe = it } },
                persist = { _ ->
                    // Held across both downloads: the cancelled one and the queued one right behind it.
                    probe!!.unwinding = true
                    saving.complete(Unit)
                    withContext(NonCancellable) { delay(400) }
                    if (persists.incrementAndGet() == 2) probe!!.unwinding = false
                    throw IOException("synthetic full disk")
                },
                await = false,
            )
            runBlocking {
                withTimeout(20_000) { saving.await() }
                vm.cancelDownload()
                vm.downloadSession(session) // queued behind the one that ignores cancellation: active, nothing shown
                vm.connect("fake-note-pro")
                delay(2_500)
            }
            assertEquals(2, persists.get(), "both the cancelled and the queued download ran")
            assertEquals(0, probe!!.connectsWhileUnwinding.get(), "connect must not run while either download uses the client")
            assertEquals(2, probe!!.connects.get(), "the explicit connect must not be ignored by a job that has not started")
        } finally { tearDown() }
    }

    @Test fun connect_that_cannot_wait_for_a_stuck_download_says_so() {
        try {
            val saving = CompletableDeferred<Unit>()
            var probe: ConnectProbe? = null
            val vm = run(
                peripheral(),
                transport = { p -> ConnectProbe(FakeBleTransport(p)).also { probe = it } },
                persist = { _ ->
                    saving.complete(Unit)
                    withContext(NonCancellable) { delay(2_000) }
                    throw IOException("synthetic full disk")
                },
                await = false,
                wipeJoinTimeout = 200.milliseconds,
            )
            runBlocking {
                withTimeout(20_000) { saving.await() }
                vm.cancelDownload()
                vm.connect("fake-note-pro")
                delay(600)
                assertEquals(DeviceUiCopy.connectBusy("en"), vm.sessionUi.value.statusMessage)
            }
            assertEquals(1, probe!!.connects.get(), "no second connect while the download is still unwinding")
        } finally { tearDown() }
    }

    @Test fun connect_during_a_download_is_ignored() {
        try {
            val saving = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val fake = peripheral()
            val vm = run(
                fake,
                persist = { audio ->
                    saving.complete(Unit)
                    release.await()
                    val out = ByteArrayOutputStream()
                    audio.writeDecryptedAudio { out.write(it) }
                    saved = out.toByteArray()
                    "rec-synthetic"
                },
                await = false,
            )
            runBlocking {
                withTimeout(20_000) { saving.await() }
                vm.connect("fake-note-pro")
                delay(200)
                assertTrue(vm.sessionUi.value.downloadingSessionId != null, "the running download is still shown")
                assertTrue(vm.sessionUi.value.statusMessage != DeviceUiCopy.unlocking("en"), "no second unlock on the live link")
                release.complete(Unit)
            }
            awaitOutcome(vm)
            assertEquals("rec-synthetic", vm.sessionUi.value.lastDownloadedRecordingId)
            assertEquals(listOf(0L), fake.syncStartOffsets)
        } finally { tearDown() }
    }

    @Test fun wipe_deletes_partials_off_the_callers_thread() {
        try {
            var deleteThread: Thread? = null
            val vm = run(peripheral(), deletePartials = { deleteThread = Thread.currentThread() }, await = false)
            assertTrue(runBlocking { vm.wipePartialDownloads() }, "no download running: done")
            assertNotNull(deleteThread)
            assertFalse(deleteThread === Thread.currentThread(), "partial deletion must not run on the caller's thread (#237)")
        } finally { tearDown() }
    }

    @Test fun wipe_after_the_download_finished_says_done() {
        try {
            val vm = run(peripheral())
            assertEquals("rec-synthetic", vm.sessionUi.value.lastDownloadedRecordingId)
            runBlocking { withTimeout(20_000) { while (vm.sessionUi.value.downloadingSessionId != null) delay(5) } }
            assertTrue(runBlocking { vm.wipePartialDownloads() }, "nothing was running")
        } finally { tearDown() }
    }

    @Test fun wipe_stops_a_running_download_and_deletes_the_partials() {
        try {
            val vm = run(peripheral(), persist = { awaitCancellation() }, await = false)
            runBlocking {
                withTimeout(20_000) { while (partials.list()!!.none { it.endsWith(".partial") }) delay(5) }
                assertTrue(vm.wipePartialDownloads(), "the download stopped and the partials went")
            }
            assertFalse(partials.exists(), "no partial or salt survives a wipe")
            assertEquals(DeviceUiCopy.downloadCancelled("en"), vm.sessionUi.value.statusMessage)
        } finally { tearDown() }
    }

    @Test fun wipe_deletes_the_partials_even_if_the_download_ignores_cancellation_and_does_not_say_done() {
        try {
            val saving = AtomicBoolean(false)
            val vm = run(
                peripheral(),
                persist = {
                    saving.set(true)
                    withContext(NonCancellable) { delay(3_000) }
                    "rec-synthetic"
                },
                await = false,
                wipeJoinTimeout = 200.milliseconds,
            )
            runBlocking {
                withTimeout(20_000) { while (!saving.get()) delay(5) }
                val waited = measureTime { assertFalse(vm.wipePartialDownloads(), "the download was still running") }
                assertTrue(waited < 2.seconds, "the wipe must not wait for the download: $waited")
            }
            assertFalse(partials.exists())
        } finally { tearDown() }
    }

    @Test fun wipe_reports_partials_it_could_not_delete_instead_of_failing() {
        try {
            val vm = run(peripheral(), deletePartials = { error("synthetic delete failure") })
            assertFalse(runBlocking { vm.wipePartialDownloads() })
        } finally { tearDown() }
    }

    @Test fun first_unlock_on_a_hung_link_ends_in_a_failure_status() {
        try {
            runBlocking {
                Dispatchers.setMain(Dispatchers.Unconfined)
                val ble = FakeBleTransport(peripheral()).apply { beforeWrite = { awaitCancellation() } }
                val vm = DeviceViewModel(ble, Importer(keys), localeTag = { "en" })
                owner.put("device", vm)
                vm.connect("fake-note-pro")
                withTimeout(30_000) {
                    while (vm.sessionUi.value.statusMessage != DeviceUiCopy.unlockFailed("en")) delay(50)
                }
            }
        } finally { tearDown() }
    }

    @Test fun read_error_on_a_complete_partial_keeps_it_and_a_retry_needs_no_new_transfer() {
        try {
            var failing = true
            val fake = peripheral()
            val vm = run(fake, open = { id, sn ->
                val store = PartialTransferFileStore.open(partials, id, sn)
                object : PartialTransfer by store {
                    override fun read(offset: Long, length: Int): ByteArray {
                        if (failing) throw IOException("synthetic read failure")
                        return store.read(offset, length)
                    }
                }
            })
            assertEquals(DeviceUiCopy.downloadFailed("en"), vm.sessionUi.value.statusMessage)
            assertTrue(partials.list()!!.any { it.endsWith(".partial") }, "the transfer is kept")
            failing = false
            vm.downloadSession(session)
            awaitOutcome(vm)
            assertEquals("rec-synthetic", vm.sessionUi.value.lastDownloadedRecordingId)
            assertContentEquals(plaintext, assertNotNull(saved))
            assertEquals(listOf(0L), fake.syncStartOffsets, "nothing is transferred again")
        } finally { tearDown() }
    }

    private fun readFailCount(): Int? =
        partials.listFiles()!!.firstOrNull { it.name.endsWith(".readfail") }?.readText()?.trim()?.toIntOrNull()

    /** Also waits for the attempt to end: downloadSession ignores a call while one is still running. */
    private fun awaitReadFailCount(vm: DeviceViewModel, n: Int) = runBlocking {
        withTimeout(20_000) {
            while (readFailCount() != n || vm.sessionUi.value.downloadingSessionId != null) delay(5)
        }
    }

    @Test fun a_read_error_that_repeats_discards_the_partial_and_the_next_download_starts_fresh() {
        try {
            var failing = true
            val fake = peripheral()
            val vm = run(fake, open = { id, sn ->
                val store = PartialTransferFileStore.open(partials, id, sn)
                object : PartialTransfer by store {
                    override fun read(offset: Long, length: Int): ByteArray {
                        if (failing) throw IOException("synthetic permanent read failure")
                        return store.read(offset, length)
                    }
                }
            })
            assertEquals(DeviceUiCopy.downloadFailed("en"), vm.sessionUi.value.statusMessage)
            assertTrue(partials.list()!!.any { it.endsWith(".partial") }, "the first failure keeps the transfer")
            assertEquals(1, readFailCount(), "and counts it")
            vm.downloadSession(session)
            awaitReadFailCount(vm, 2)
            vm.downloadSession(session) // the third failure in a row
            runBlocking { withTimeout(20_000) { while (partials.list()!!.any { it.endsWith(".partial") }) delay(5) } }
            runBlocking { withTimeout(20_000) { while (vm.sessionUi.value.statusMessage != DeviceUiCopy.savedDownloadUnreadable("en")) delay(5) } }
            // After the status, which is set once the delete has finished: .partial goes first, so it says nothing.
            assertNull(readFailCount(), "the count goes with the partial")
            failing = false
            vm.downloadSession(session)
            awaitOutcome(vm)
            assertEquals("rec-synthetic", vm.sessionUi.value.lastDownloadedRecordingId)
            assertContentEquals(plaintext, assertNotNull(saved))
            assertEquals(listOf(0L, 0L), fake.syncStartOffsets, "the third failure discarded it, so the file came again")
        } finally { owner.clear(); Dispatchers.resetMain() }
    }

    @Test fun a_good_read_resets_the_count_so_scattered_errors_never_discard() {
        try {
            var readFails = true
            var saveFails = false
            val fake = peripheral()
            val vm = run(
                fake,
                persist = { audio ->
                    val out = ByteArrayOutputStream()
                    audio.writeDecryptedAudio {
                        check(!saveFails) { "synthetic full disk" }
                        out.write(it)
                    }
                    saved = out.toByteArray()
                    "rec-synthetic"
                },
                open = { id, sn ->
                    val store = PartialTransferFileStore.open(partials, id, sn)
                    object : PartialTransfer by store {
                        override fun read(offset: Long, length: Int): ByteArray {
                            if (readFails) throw IOException("synthetic read failure")
                            return store.read(offset, length)
                        }
                    }
                },
            )
            assertEquals(1, readFailCount())
            readFails = false; saveFails = true // this attempt reads fine and fails to save
            vm.downloadSession(session)
            runBlocking { withTimeout(20_000) { while (readFailCount() != null || vm.sessionUi.value.downloadingSessionId != null) delay(5) } }
            assertTrue(partials.list()!!.any { it.endsWith(".partial") }, "still kept")
            readFails = true; saveFails = false
            vm.downloadSession(session)
            awaitReadFailCount(vm, 1) // counting restarted at 1, not 2
            vm.downloadSession(session)
            awaitReadFailCount(vm, 2)
            assertTrue(partials.list()!!.any { it.endsWith(".partial") }, "two scattered errors never reached the limit")
        } finally { owner.clear(); Dispatchers.resetMain() }
    }

    @Test fun cancel_during_decrypt_keeps_the_partial_and_a_retry_needs_no_new_transfer() {
        try {
            // More than one decrypt chunk: the cancellation surfaces at the check before the next one.
            val longPlain = Random(22).nextBytes(3 * PlaudFileCrypto.STREAM_CHUNK_BYTES)
            val longFile = (createFileDecryptor() as JcaFileDecryptor).encryptFile(pems.first, longPlain)
            val fake = peripheral(file = longFile)
            val cancelling = AtomicBoolean(true)
            val released = CompletableDeferred<Unit>()
            val vm = run(
                fake,
                persist = { audio ->
                    val out = ByteArrayOutputStream()
                    audio.writeDecryptedAudio {
                        if (cancelling.getAndSet(false)) running!!.cancelDownload()
                        out.write(it)
                    }
                    saved = out.toByteArray()
                    "rec-synthetic"
                },
                open = { id, sn ->
                    val store = PartialTransferFileStore.open(partials, id, sn)
                    object : PartialTransfer by store {
                        override fun close() {
                            store.close()
                            released.complete(Unit)
                        }

                        override fun delete() {
                            store.delete()
                            released.complete(Unit)
                        }
                    }
                },
                await = false,
            )
            runBlocking { withTimeout(60_000) { released.await() } }
            assertEquals(DeviceUiCopy.downloadCancelled("en"), vm.sessionUi.value.statusMessage)
            assertTrue(partials.list()!!.any { it.endsWith(".partial") }, "the transfer is kept")
            vm.downloadSession(session)
            awaitOutcome(vm)
            assertEquals("rec-synthetic", vm.sessionUi.value.lastDownloadedRecordingId)
            assertContentEquals(longPlain, assertNotNull(saved))
            assertEquals(listOf(0L), fake.syncStartOffsets, "nothing is transferred again")
        } finally { tearDown() }
    }

    @Test fun wrong_key_on_a_complete_partial_deletes_it_without_starting_a_transfer() {
        try {
            val other = rsaKeyPairToPem()
            val reimported = keys.copy(publicKeyPem = other.first, privateKeyPem = other.second)
            val earlier = PartialTransferFileStore.open(partials, session, serial)
            earlier.write(encrypted)
            assertEquals(encrypted.size.toLong(), earlier.length())
            earlier.close()
            val fake = peripheral(identity = reimported)
            val vm = run(fake, identity = reimported)
            assertEquals(DeviceUiCopy.downloadFailed("en"), vm.sessionUi.value.statusMessage)
            assertEquals(emptyList(), fake.syncStartOffsets)
            assertTrue(partials.list()!!.none { it.endsWith(".partial") || it.endsWith(".offset") })
        } finally { tearDown() }
    }

    @Test fun undecryptable_download_clears_the_partial_so_a_retry_starts_over() {
        try {
            val vm = run(peripheral(file = ByteArray(encrypted.size) { 0x5A }))
            assertEquals(DeviceUiCopy.downloadFailed("en"), vm.sessionUi.value.statusMessage)
            assertTrue(partials.list()!!.none { it.endsWith(".partial") || it.endsWith(".offset") })
        } finally { tearDown() }
    }
}
