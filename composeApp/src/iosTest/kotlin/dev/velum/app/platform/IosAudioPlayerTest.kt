@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.velum.app.platform

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.Foundation.writeToFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IosAudioPlayerTest {
    @Test
    fun filePreparationSeekingReplacementAndRelease() = runBlocking {
        val path = NSTemporaryDirectory() + NSUUID().UUIDString + ".wav"
        val bytes = ByteArray(32044)
        fun text(offset: Int, value: String) { value.encodeToByteArray().copyInto(bytes, offset) }
        fun number(offset: Int, value: Int, size: Int) {
            repeat(size) { bytes[offset + it] = (value ushr (8 * it)).toByte() }
        }
        text(0, "RIFF"); number(4, 32036, 4); text(8, "WAVEfmt ")
        number(16, 16, 4); number(20, 1, 2); number(22, 1, 2)
        number(24, 16000, 4); number(28, 32000, 4)
        number(32, 2, 2); number(34, 16, 2); text(36, "data"); number(40, 32000, 4)
        bytes.usePinned { assertTrue(NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()).writeToFile(path, true)) }
        val player = createAudioPlayer()
        try {
            assertTrue(player.prepare(path))
            assertEquals(1000L, player.durationMs.value)
            assertFalse(player.isPlaying.value)
            player.seekTo(400)
            assertEquals(400L, player.positionMs.value)
            player.seekBy(Long.MAX_VALUE)
            assertEquals(1000L, player.positionMs.value)
            player.seekTo(-1)
            assertEquals(0L, player.positionMs.value)
            player.seekBy(Long.MIN_VALUE)
            assertEquals(0L, player.positionMs.value)
            assertFalse(player.prepare(path + ".missing"))
            assertEquals(0L, player.durationMs.value)
            assertEquals(0L, player.positionMs.value)
            assertTrue(player.prepare(path))
            player.pause()
            assertFalse(player.isPlaying.value)
            player.release()
            player.release()
            assertEquals(0L, player.durationMs.value)
            assertEquals(0L, player.positionMs.value)
            player.play()
            assertFalse(player.isPlaying.value)
        } finally {
            player.release()
            NSFileManager.defaultManager.removeItemAtPath(path, null)
        }
    }

    @Test
    fun preferencesAndUnpreparedControls() = runBlocking {
        val player = createAudioPlayer()
        assertFalse(player.prepare(""))
        for (speed in listOf(1.25f, 1.5f, 2f, 0.75f, 1f)) {
            player.cycleSpeed()
            assertEquals(speed, player.playbackSpeed.value)
        }
        player.setSkipSilence(true)
        assertTrue(player.skipSilence.value)
        player.seekTo(50)
        player.pause()
        player.play()
        assertFalse(player.isPlaying.value)
        assertEquals(0L, player.positionMs.value)
        player.release()
    }
}
