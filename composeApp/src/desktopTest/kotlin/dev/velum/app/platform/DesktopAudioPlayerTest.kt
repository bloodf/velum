package dev.velum.app.platform

import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking

class DesktopAudioPlayerTest {
    private fun wav(): File = File.createTempFile("velum-player", ".wav").apply {
        val format = AudioFormat(8000f, 16, 1, true, false)
        AudioInputStream(ByteArrayInputStream(ByteArray(16000)), format, 8000).use {
            AudioSystem.write(it, AudioFileFormat.Type.WAVE, this)
        }
    }

    private class Output : DesktopAudioOutput {
        val worker = Thread.currentThread()
        val entered = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val closed = CountDownLatch(1)
        @Volatile var frames = 0L
        var closes = 0
        override val playedFrames get() = frames
        override fun write(bytes: ByteArray, length: Int): Int {
            entered.countDown()
            check(unblock.await(5, TimeUnit.SECONDS))
            return length
        }
        override fun drain() = Unit
        override fun close() {
            closes++
            closed.countDown()
            // Deliberately leave write blocked: stale workers must not publish state.
        }
    }

    @Test fun validatesWithoutOpeningOutput() = runBlocking {
        val file = wav()
        val player = DesktopAudioPlayer { error("prepare opened output") }
        try {
            assertTrue(player.prepare(file.path))
            assertEquals(1000L, player.durationMs.value)
            assertFalse(player.supportsPlaybackSpeed)
            assertFalse(player.supportsSkipSilence)
            player.seekTo(500)
            assertEquals(500L, player.positionMs.value)
            player.seekBy(-15000)
            assertEquals(0L, player.positionMs.value)
            player.seekTo(9000)
            assertEquals(1000L, player.positionMs.value)
            assertFalse(player.prepare(file.path + ".missing"))
            assertEquals(0L, player.durationMs.value)
            file.writeText("not audio")
            assertFalse(player.prepare(file.path))
        } finally { player.release(); file.delete() }
    }

    @Test fun pauseSeekReplacementAndStaleWorkerCleanup() = runBlocking {
        val file = wav()
        val outputs = mutableListOf<Output>()
        val player = DesktopAudioPlayer { Output().also { synchronized(outputs) { outputs.add(it) } } }
        fun output(index: Int): Output {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (synchronized(outputs) { outputs.size <= index }) {
                check(System.nanoTime() < deadline)
                Thread.yield()
            }
            return synchronized(outputs) { outputs[index] }.also {
                assertTrue(it.entered.await(5, TimeUnit.SECONDS))
            }
        }
        try {
            assertTrue(player.prepare(file.path))
            player.play()
            val first = output(0)
            first.frames = 2000
            player.pause()
            assertFalse(player.isPlaying.value)
            assertEquals(250L, player.positionMs.value)
            assertEquals(1, first.closes)
            player.seekTo(500)
            player.play()
            val second = output(1)
            player.seekTo(750)
            val sought = output(2)
            assertEquals(750L, player.positionMs.value)
            assertTrue(player.isPlaying.value)
            assertTrue(player.prepare(file.path))
            player.seekTo(125)
            first.unblock.countDown()
            second.unblock.countDown()
            sought.unblock.countDown()
            listOf(first, second, sought).forEach {
                it.worker.join(5000)
                assertFalse(it.worker.isAlive)
                assertEquals(1, it.closes)
            }
            assertEquals(125L, player.positionMs.value)
            assertFalse(player.isPlaying.value)
            player.play()
            val third = output(3)
            player.release()
            player.release()
            third.unblock.countDown()
            third.worker.join(5000)
            assertFalse(third.worker.isAlive)
            assertEquals(0L, player.durationMs.value)
            assertEquals(0L, player.positionMs.value)
            assertFalse(player.isPlaying.value)
            assertEquals(1, third.closes)
        } finally {
            player.release()
            synchronized(outputs) { outputs.forEach { it.unblock.countDown() } }
            file.delete()
        }
    }

    @Test fun releaseInvalidatesPendingPreparation() = runBlocking {
        val file = wav()
        val player = DesktopAudioPlayer { error("No output expected") }
        try {
            val pending = async(start = CoroutineStart.UNDISPATCHED) { player.prepare(file.path) }
            player.release()
            assertFalse(pending.await())
            assertEquals(0L, player.durationMs.value)
            assertFalse(player.isPlaying.value)
        } finally { player.release(); file.delete() }
    }

    @Test fun unavailableOutputStopsPlayback() = runBlocking {
        val file = wav()
        val attempted = CountDownLatch(1)
        var worker: Thread? = null
        val player = DesktopAudioPlayer {
            worker = Thread.currentThread()
            attempted.countDown()
            error("No output device")
        }
        try {
            assertTrue(player.prepare(file.path))
            player.play()
            assertTrue(attempted.await(5, TimeUnit.SECONDS))
            worker!!.join(5000)
            assertFalse(player.isPlaying.value)
            assertEquals(0L, player.positionMs.value)
            assertEquals(1000L, player.durationMs.value)
        } finally { player.release(); file.delete() }
    }

    @Test fun reachesEnd() = runBlocking {
        val file = wav()
        val ended = CountDownLatch(1)
        val player = DesktopAudioPlayer {
            object : DesktopAudioOutput {
                override val playedFrames = 8000L
                override fun write(bytes: ByteArray, length: Int) = length
                override fun drain() = Unit
                override fun close() { ended.countDown() }
            }
        }
        try {
            assertTrue(player.prepare(file.path))
            player.play()
            assertTrue(ended.await(5, TimeUnit.SECONDS))
            assertEquals(1000L, player.positionMs.value)
            assertFalse(player.isPlaying.value)
        } finally { player.release(); file.delete() }
    }
}
