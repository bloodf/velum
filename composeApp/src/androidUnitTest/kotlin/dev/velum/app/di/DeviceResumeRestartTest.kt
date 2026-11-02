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
import dev.velum.ble.fake.FakeBleTransport
import dev.velum.ble.fake.FakePlaudPeripheral
import dev.velum.ble.fake.TransferFaults
import dev.velum.core.security.DeviceKeyImporter
import dev.velum.protocol.DeviceIdentityKeys
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
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
        persist: (DownloadedSessionAudio) -> String = { audio ->
            val out = ByteArrayOutputStream()
            audio.writeDecryptedAudio { out.write(it) }
            saved = out.toByteArray()
            "rec-synthetic"
        },
        open: (Long, String?) -> PartialTransfer? = { id, sn -> PartialTransferFileStore.open(partials, id, sn) },
    ): DeviceViewModel = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val vm = DeviceViewModel(
            FakeBleTransport(peripheral),
            Importer(keys),
            persistDownloadedSession = { persist(it) },
            localeTag = { "en" },
            openPartialTransfer = open,
        )
        owner.put("device", vm)
        vm.connect("fake-note-pro")
        withTimeout(20_000) { while (vm.sessionUi.value.sessions.isEmpty()) delay(5) }
        vm.downloadSession(session)
        withTimeout(60_000) {
            while (vm.sessionUi.value.lastDownloadedRecordingId == null && vm.sessionUi.value.statusMessage != DeviceUiCopy.downloadFailed("en")) delay(5)
        }
        vm
    }

    private var saved: ByteArray? = null
    private val owner = ViewModelStore()

    private fun peripheral(
        faults: TransferFaults = TransferFaults(),
        stopCrc: FakePlaudPeripheral.StopCrc = FakePlaudPeripheral.StopCrc.NotProvided,
    ) = FakePlaudPeripheral(createSessionCipher(), keys, encrypted, sessionId = session, requirePublicKey = false, chunkSize = 200, faults = faults, stopCrc = stopCrc)

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
            runBlocking {
                withTimeout(60_000) {
                    while (vm.sessionUi.value.lastDownloadedRecordingId == null && vm.sessionUi.value.statusMessage != DeviceUiCopy.downloadFailed("en")) delay(5)
                }
            }
            assertEquals("rec-synthetic", vm.sessionUi.value.lastDownloadedRecordingId)
            assertContentEquals(plaintext, assertNotNull(saved))
        } finally { owner.clear(); Dispatchers.resetMain() }
    }

    @Test fun undecryptable_download_clears_the_partial_so_a_retry_starts_over() {
        try {
            val vm = run(peripheral(), persist = { error("synthetic decrypt failure") })
            assertEquals(DeviceUiCopy.downloadFailed("en"), vm.sessionUi.value.statusMessage)
            assertTrue(partials.list()!!.none { it.endsWith(".partial") || it.endsWith(".offset") })
        } finally { owner.clear(); Dispatchers.resetMain() }
    }
}
