package dev.velum.ble

import dev.velum.ble.client.FileSessionDownloadProgressStore
import dev.velum.ble.client.SessionDownloadCheckpoint
import dev.velum.ble.client.SessionDownloadStatus
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import dev.velum.protocol.Crc16Ccitt

class SessionDownloadProgressStoreTest {
    @Test
    fun atomicSave_roundTripsAndSurvivesReread() {
        val dir = Files.createTempDirectory("velum-ckpt-").toFile()
        val store = FileSessionDownloadProgressStore(dir)
        val ckpt = SessionDownloadCheckpoint(
            sessionId = 55L,
            expectedSizeBytes = 10_000,
            verifiedOffset = 4_000,
            runningCrc = Crc16Ccitt.compute(ByteArray(4_000) { it.toByte() }),
            partialPath = "/tmp/partial.bin",
            status = SessionDownloadStatus.InProgress,
        )
        store.save(ckpt)
        assertEquals(ckpt, store.load(55L))

        val store2 = FileSessionDownloadProgressStore(dir)
        assertEquals(ckpt, store2.load(55L))

        store.clear(55L)
        assertNull(store.load(55L))
        assertNull(store2.load(55L))
    }
}
