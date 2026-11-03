package dev.velum.app.di

import androidx.lifecycle.ViewModelStore
import dev.velum.app.i18n.DeviceUiCopy
import dev.velum.app.platform.PartialTransfer
import dev.velum.app.platform.PartialTransferFileStore
import dev.velum.app.ui.DeviceViewModel
import dev.velum.app.ui.DownloadWipeResult
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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
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
        } finally { owner.clear(); Dispatchers.resetMain() }
    }

    @Test fun lost_link_during_a_download_resumes_in_the_same_run() {
        try {
            val fake = peripheral(TransferFaults(disconnectAt = setOf(15_000)))
            val vm = run(fake)
            assertEquals("rec-synthetic", vm.sessionUi.value.lastDownloadedRecordingId)
            assertEquals(listOf(0L, 15_000L), fake.syncStartOffsets)
            assertContentEquals(plaintext, assertNotNull(saved))
            assertEquals(1, vm.sessionUi.value.sessions.size, "session list still works on the reconnected client")
        } finally { owner.clear(); Dispatchers.resetMain() }
    }

    @Test fun a_matching_device_crc_is_reported_as_verified() {
        try {
            val vm = run(peripheral(stopCrc = FakePlaudPeripheral.StopCrc.Ccitt))
            assertEquals(CrcStatus.Verified, vm.sessionUi.value.lastDownloadCrc)
            assertContentEquals(plaintext, assertNotNull(saved))
        } finally { owner.clear(); Dispatchers.resetMain() }
    }

    @Test fun a_device_with_no_crc_is_reported_as_not_provided() {
        try {
            val vm = run(peripheral())
            assertEquals(CrcStatus.NotProvided, vm.sessionUi.value.lastDownloadCrc)
        } finally { owner.clear(); Dispatchers.resetMain() }
    }

    @Test fun real_device_crc_mismatch_keeps_the_recording_and_says_so() {
        try {
            val vm = run(peripheral(stopCrc = FakePlaudPeripheral.StopCrc.Wrong))
            assertIs<CrcStatus.Mismatch>(vm.sessionUi.value.lastDownloadCrc)
            assertEquals("rec-synthetic", vm.sessionUi.value.lastDownloadedRecordingId)
            assertEquals(DeviceUiCopy.downloadedSessionCrcMismatch("en", session), vm.sessionUi.value.statusMessage)
            assertContentEquals(plaintext, assertNotNull(saved))
        } finally { owner.clear(); Dispatchers.resetMain() }
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
        } finally { owner.clear(); Dispatchers.resetMain() }
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
        } finally { owner.clear(); Dispatchers.resetMain() }
    }

    @Test fun a_verified_transfer_stores_no_warning() {
        try {
            val warnings = mutableListOf<String?>()
            run(peripheral(stopCrc = FakePlaudPeripheral.StopCrc.Ccitt), persist = { audio ->
                warnings += audio.processingWarning
                val out = ByteArrayOutputStream(); audio.writeDecryptedAudio { out.write(it) }; saved = out.toByteArray(); "rec-synthetic"
            })
            assertEquals(listOf<String?>(null), warnings)
        } finally { owner.clear(); Dispatchers.resetMain() }
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
        } finally { owner.clear(); Dispatchers.resetMain() }
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
        } finally { owner.clear(); Dispatchers.resetMain() }
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
        } finally { owner.clear(); Dispatchers.resetMain() }
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
        } finally { owner.clear(); Dispatchers.resetMain() }
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
        } finally { owner.clear(); Dispatchers.resetMain() }
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
        } finally { owner.clear(); Dispatchers.resetMain() }
    }

    /** Starts a download held in NonCancellable saving, cancels it, then queues and cancels a second behind it. */
    private fun cancelledTwice(
        hold: suspend () -> Unit,
        wipeJoinTimeout: Duration = 15.seconds,
        transport: (FakePlaudPeripheral) -> BleTransport = { FakeBleTransport(it) },
    ): DeviceViewModel {
        val saving = CompletableDeferred<Unit>()
        val vm = run(
            peripheral(),
            persist = {
                saving.complete(Unit)
                withContext(NonCancellable) { hold() }
                "rec-synthetic"
            },
            await = false,
            wipeJoinTimeout = wipeJoinTimeout,
            transport = transport,
        )
        runBlocking { withTimeout(20_000) { saving.await() } }
        vm.cancelDownload()
        vm.downloadSession(session)
        vm.cancelDownload()
        return vm
    }

    @Test fun wipe_waits_for_a_download_cancelled_before_a_queued_one() {
        try {
            val finished = AtomicBoolean(false)
            val vm = cancelledTwice(hold = { delay(1_000); finished.set(true) })
            runBlocking { assertEquals(DownloadWipeResult.Clean, vm.wipePartialDownloads()) }
            assertTrue(finished.get(), "the wipe finished while the first download was still saving")
        } finally { owner.clear(); Dispatchers.resetMain() }
    }

    @Test fun connect_waits_for_a_download_cancelled_before_a_queued_one() {
        try {
            val release = CompletableDeferred<Unit>()
            var probe: ConnectProbe? = null
            val vm = cancelledTwice(
                hold = { release.await() },
                wipeJoinTimeout = 200.milliseconds,
                transport = { p -> ConnectProbe(FakeBleTransport(p)).also { probe = it } },
            )
            vm.connect("fake-note-pro")
            runBlocking {
                delay(600)
                assertEquals(DeviceUiCopy.connectBusy("en"), vm.sessionUi.value.statusMessage)
                assertEquals(1, probe!!.connects.get(), "no second connect while the first download is still saving")
                release.complete(Unit)
                withTimeout(30_000) { vm.awaitDownloads() }
            }
        } finally { owner.clear(); Dispatchers.resetMain() }
    }

    @Test fun cancel_stops_every_queued_download() {
        try {
            val saving = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val vm = run(
                peripheral(),
                persist = {
                    saving.complete(Unit)
                    withContext(NonCancellable) { release.await() }
                    "rec-synthetic"
                },
                await = false,
            )
            runBlocking {
                withTimeout(20_000) { saving.await() }
                vm.cancelDownload()
                vm.downloadSession(session)
                vm.downloadSession(session)
                vm.cancelDownload()
                release.complete(Unit)
                withTimeout(30_000) { vm.awaitDownloads() }
                val restarted = withTimeoutOrNull(1_500) { vm.sessionUi.first { it.downloadingSessionId != null } }
                assertEquals(null, restarted, "a cancelled queued download started anyway")
            }
        } finally { owner.clear(); Dispatchers.resetMain() }
    }

    private fun heldInSaving(
        release: CompletableDeferred<Unit>,
        saves: AtomicInteger,
        wipeJoinTimeout: Duration = 15.seconds,
    ): DeviceViewModel {
        val saving = CompletableDeferred<Unit>()
        val vm = run(
            peripheral(),
            persist = {
                saves.incrementAndGet()
                saving.complete(Unit)
                withContext(NonCancellable) { release.await() }
                "rec-synthetic"
            },
            await = false,
            wipeJoinTimeout = wipeJoinTimeout,
        )
        runBlocking { withTimeout(20_000) { saving.await() } }
        return vm
    }

    @Test fun a_download_runs_to_completion_after_a_cancel() {
        try {
            val vm = run(peripheral(), await = false)
            vm.cancelDownload()
            runBlocking { withTimeout(30_000) { vm.awaitDownloads() } }
            vm.downloadSession(session)
            runBlocking {
                withTimeout(60_000) { vm.sessionUi.first { it.lastDownloadedRecordingId == "rec-synthetic" } }
            }
            assertContentEquals(plaintext, assertNotNull(saved))
        } finally { owner.clear(); Dispatchers.resetMain() }
    }

    @Test fun a_tap_during_a_wipe_starts_nothing() {
        try {
            val saves = AtomicInteger()
            val vm = run(peripheral(), persist = { saves.incrementAndGet(); "rec-synthetic" })
            runBlocking {
                vm.holdingDownloads {
                    vm.wipePartialDownloads()
                    vm.downloadSession(session)
                    delay(300)
                    assertEquals(null, vm.sessionUi.value.downloadingSessionId)
                }
                vm.awaitDownloads()
            }
            assertEquals(1, saves.get(), "only the download from before the wipe saved")
        } finally { owner.clear(); Dispatchers.resetMain() }
    }

    @Test fun a_download_queued_before_a_wipe_does_not_start_during_it() {
        try {
            val release = CompletableDeferred<Unit>()
            val saves = AtomicInteger()
            val vm = heldInSaving(release, saves)
            vm.cancelDownload()
            vm.downloadSession(session)
            runBlocking {
                vm.holdingDownloads {
                    release.complete(Unit)
                    withTimeout(30_000) { vm.awaitDownloads() }
                }
            }
            assertEquals(1, saves.get(), "the queued download started during the wipe")
        } finally { owner.clear(); Dispatchers.resetMain() }
    }

    @Test fun a_failed_wipe_lets_downloads_start_again() {
        try {
            val saves = AtomicInteger()
            val vm = run(peripheral(), persist = { saves.incrementAndGet(); "rec-synthetic" })
            runBlocking {
                assertFailsWith<IllegalStateException> { vm.holdingDownloads { error("synthetic wipe failure") } }
                vm.downloadSession(session)
                withTimeout(30_000) { vm.awaitDownloads() }
            }
            assertEquals(2, saves.get())
        } finally { owner.clear(); Dispatchers.resetMain() }
    }

    @Test fun disconnect_does_not_wait_forever_for_a_hung_download() {
        try {
            val release = CompletableDeferred<Unit>()
            val vm = heldInSaving(release, AtomicInteger(), wipeJoinTimeout = 200.milliseconds)
            vm.disconnect()
            runBlocking {
                withTimeout(10_000) { vm.sessionUi.first { it.sessions.isEmpty() } }
                release.complete(Unit)
                withTimeout(30_000) { vm.awaitDownloads() }
            }
        } finally { owner.clear(); Dispatchers.resetMain() }
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
        } finally { owner.clear(); Dispatchers.resetMain() }
    }

    @Test fun wipe_deletes_partials_off_the_callers_thread() {
        try {
            var deleteThread: Thread? = null
            val vm = run(peripheral(), deletePartials = { deleteThread = Thread.currentThread() }, await = false)
            runBlocking { vm.wipePartialDownloads() }
            assertNotNull(deleteThread)
            assertFalse(deleteThread === Thread.currentThread(), "partial deletion must not run on the caller's thread (#237)")
        } finally { owner.clear(); Dispatchers.resetMain() }
    }

    @Test fun wipe_stops_a_running_download_and_deletes_the_partials() {
        try {
            val vm = run(peripheral(), persist = { awaitCancellation() }, await = false)
            runBlocking {
                withTimeout(20_000) { while (partials.list()!!.none { it.endsWith(".partial") }) delay(5) }
                vm.wipePartialDownloads()
            }
            assertFalse(partials.exists(), "no partial or salt survives a wipe")
            assertEquals(DeviceUiCopy.downloadCancelled("en"), vm.sessionUi.value.statusMessage)
        } finally { owner.clear(); Dispatchers.resetMain() }
    }

    @Test fun wipe_deletes_the_partials_even_if_the_download_ignores_cancellation() {
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
                val waited = measureTime { assertEquals(DownloadWipeResult.StillRunning, vm.wipePartialDownloads()) }
                assertTrue(waited < 2.seconds, "the wipe must not wait for the download: $waited")
                withTimeout(30_000) { vm.awaitDownloads() }
            }
            assertFalse(partials.exists())
        } finally { owner.clear(); Dispatchers.resetMain() }
    }

    @Test fun wipe_reports_partials_it_could_not_delete_instead_of_failing() {
        try {
            val vm = run(peripheral(), deletePartials = { error("synthetic delete failure") })
            assertEquals(DownloadWipeResult.PartialsLeft, runBlocking { vm.wipePartialDownloads() })
        } finally { owner.clear(); Dispatchers.resetMain() }
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
        } finally { owner.clear(); Dispatchers.resetMain() }
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
        } finally { owner.clear(); Dispatchers.resetMain() }
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
        } finally { owner.clear(); Dispatchers.resetMain() }
    }

    @Test fun undecryptable_download_clears_the_partial_so_a_retry_starts_over() {
        try {
            val vm = run(peripheral(file = ByteArray(encrypted.size) { 0x5A }))
            assertEquals(DeviceUiCopy.downloadFailed("en"), vm.sessionUi.value.statusMessage)
            assertTrue(partials.list()!!.none { it.endsWith(".partial") || it.endsWith(".offset") })
        } finally { owner.clear(); Dispatchers.resetMain() }
    }
}
