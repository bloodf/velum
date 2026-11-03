package dev.velum.app.di

import androidx.lifecycle.ViewModelStore
import dev.velum.app.i18n.DeviceUiCopy
import dev.velum.app.platform.PartialTransfer
import dev.velum.app.platform.PartialTransferFileStore
import dev.velum.app.ui.DeviceViewModel
import dev.velum.app.ui.DownloadedSessionAudio
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
import kotlinx.coroutines.withTimeout

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
    ): DeviceViewModel = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val vm = DeviceViewModel(
            FakeBleTransport(peripheral),
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

    @Test fun real_device_crc_mismatch_keeps_the_recording_and_says_so() {
        try {
            val vm = run(peripheral(stopCrc = FakePlaudPeripheral.StopCrc.Wrong))
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
                val waited = measureTime { assertTrue(vm.wipePartialDownloads()) }
                assertTrue(waited < 2.seconds, "the wipe must not wait for the download: $waited")
            }
            assertFalse(partials.exists())
        } finally { owner.clear(); Dispatchers.resetMain() }
    }

    @Test fun wipe_reports_partials_it_could_not_delete_instead_of_failing() {
        try {
            val vm = run(peripheral(), deletePartials = { error("synthetic delete failure") })
            assertFalse(runBlocking { vm.wipePartialDownloads() })
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
            assertTrue(partials.list()!!.any { it.endsWith(".readfail") }, "and counts it")
            vm.downloadSession(session)
            runBlocking { withTimeout(20_000) { while (partials.list()!!.any { it.endsWith(".partial") }) delay(5) } }
            assertTrue(partials.list()!!.none { it.endsWith(".readfail") }, "the count goes with the partial")
            failing = false
            vm.downloadSession(session)
            awaitOutcome(vm)
            assertEquals("rec-synthetic", vm.sessionUi.value.lastDownloadedRecordingId)
            assertContentEquals(plaintext, assertNotNull(saved))
            assertEquals(listOf(0L, 0L), fake.syncStartOffsets, "the second failure discarded it, so the file came again")
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
