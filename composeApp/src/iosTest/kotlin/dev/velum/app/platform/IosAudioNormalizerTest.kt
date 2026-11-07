@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.velum.app.platform

import kotlinx.cinterop.*
import kotlinx.coroutines.*
import platform.AudioToolbox.*
import platform.CoreAudioTypes.*
import platform.Foundation.*
import platform.CoreFoundation.CFURLCreateFromFileSystemRepresentation
import platform.CoreFoundation.CFRelease
import platform.posix.memcpy
import kotlin.test.*

class IosAudioNormalizerTest {
    @Test
    fun stereoWavResamplesAndPublishesIndependentMonoCopies() = runBlocking {
        val source = wavFixture()
        val outputs = mutableListOf<String>()
        try {
            repeat(2) {
                val result = AudioNormalizer().normalizeToWav(source)
                outputs += result.wavPath
                assertEquals(16_000, result.sampleRateHz)
                assertTrue(result.durationMs in 990L..1010L)
                val bytes = read(result.wavPath)
                assertEquals("RIFF", bytes.decodeToString(0, 4))
                assertEquals("WAVE", bytes.decodeToString(8, 12))
                assertEquals(1, unsigned(bytes, 22, 2))
                assertEquals(16_000, unsigned(bytes, 24, 4))
                assertEquals(16, unsigned(bytes, 34, 2))
                assertEquals(bytes.size - 44, unsigned(bytes, 40, 4))
                assertEquals(sha256Bytes(bytes), result.contentSha256)
                // Equal and opposite channels must cancel when downmixed, including across buffer boundaries.
                assertTrue(bytes.drop(44).all { it == 0.toByte() })
            }
            assertNotEquals(outputs[0], outputs[1])
            assertTrue(NSFileManager.defaultManager.fileExistsAtPath(source))
            assertContentEquals(read(outputs[0]), read(outputs[1]))
        } finally {
            (outputs + source).forEach { NSFileManager.defaultManager.removeItemAtPath(it, null) }
        }
    }

    @Test
    fun sourceHashNormalizationAndDuplicateCleanupUsePersistentOwnedFiles() = runBlocking {
        val fixture = wavFixture()
        val cache = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).first() as String
        val sourceDirectory = "$cache/imports"
        val source = "$sourceDirectory/${NSUUID().UUIDString}.wav"
        val outputs = mutableListOf<String>()
        try {
            assertTrue(NSFileManager.defaultManager.createDirectoryAtPath(sourceDirectory, true, null, null))
            assertTrue(NSFileManager.defaultManager.copyItemAtPath(fixture, source, null))
            assertTrue(isImportCacheCopy(source))
            // AudioImportPipeline hashes the source before normalizing, then keys the row by both hashes.
            val sourceHash = sha256File(source)
            assertEquals(sha256Bytes(read(fixture)), sourceHash)
            val kept = AudioNormalizer().normalizeToWav(source)
            outputs += kept.wavPath
            val applicationSupport = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true).first() as String
            assertTrue(kept.wavPath.startsWith("$applicationSupport/velum/recordings/"))
            assertTrue(isAppOwnedAudioPath(kept.wavPath))
            assertFalse(isImportCacheCopy(kept.wavPath))
            assertEquals(kept.contentSha256, sha256File(kept.wavPath))
            assertEquals(sourceHash, sha256File(source))

            val duplicate = AudioNormalizer().normalizeToWav(source)
            outputs += duplicate.wavPath
            assertNotEquals(kept.wavPath, duplicate.wavPath)
            assertEquals(kept.contentSha256, duplicate.contentSha256)
            // The pipeline discards the duplicate WAV and its scratch source, preserving the kept row's file.
            assertTrue(deleteFileAtPath(duplicate.wavPath))
            assertTrue(deleteFileIfPresent(source))
            assertFalse(NSFileManager.defaultManager.fileExistsAtPath(source))
            assertFalse(NSFileManager.defaultManager.fileExistsAtPath(duplicate.wavPath))
            assertTrue(NSFileManager.defaultManager.fileExistsAtPath(kept.wavPath))
            assertEquals(kept.contentSha256, sha256File(kept.wavPath))
            assertFalse(deleteFileAtPath(fixture))
        } finally {
            outputs.forEach { deleteFileIfPresent(it) }
            deleteFileIfPresent(source)
            NSFileManager.defaultManager.removeItemAtPath(fixture, null)
        }
    }

    @Test
    fun nativeCafContainerDecodes() = runBlocking {
        val source = NSTemporaryDirectory() + NSUUID().UUIDString + ".caf"
        var output: String? = null
        try {
            memScoped {
                val format = alloc<AudioStreamBasicDescription>()
                format.mSampleRate = 16_000.0
                format.mFormatID = kAudioFormatLinearPCM
                format.mFormatFlags = kAudioFormatFlagIsSignedInteger or kAudioFormatFlagIsPacked
                format.mBytesPerPacket = 2u; format.mFramesPerPacket = 1u
                format.mBytesPerFrame = 2u; format.mChannelsPerFrame = 1u
                format.mBitsPerChannel = 16u; format.mReserved = 0u
                val ref = alloc<ExtAudioFileRefVar>()
                val pathBytes = source.encodeToByteArray()
                val url = pathBytes.usePinned {
                    CFURLCreateFromFileSystemRepresentation(null, it.addressOf(0).reinterpret(), pathBytes.size.toLong(), false)
                }!!
                try {
                    assertEquals(0, ExtAudioFileCreateWithURL(url, kAudioFileCAFType, format.ptr, null, kAudioFileFlags_EraseFile, ref.ptr))
                } finally {
                    CFRelease(url)
                }
                val file = ref.value!!
                try {
                    val samples = allocArray<ShortVar>(1600)
                    repeat(1600) { samples[it] = 1000 }
                    val buffer = alloc<AudioBufferList>()
                    buffer.mNumberBuffers = 1u
                    buffer.mBuffers[0].mNumberChannels = 1u
                    buffer.mBuffers[0].mDataByteSize = 3200u
                    buffer.mBuffers[0].mData = samples
                    assertEquals(0, ExtAudioFileWrite(file, 1600u, buffer.ptr))
                } finally {
                    assertEquals(0, ExtAudioFileDispose(file))
                }
            }
            val result = AudioNormalizer().normalizeToWav(source)
            output = result.wavPath
            assertEquals(100L, result.durationMs)
            assertEquals(3244, read(result.wavPath).size)
        } finally {
            listOfNotNull(source, output).forEach { NSFileManager.defaultManager.removeItemAtPath(it, null) }
        }
    }

    @Test
    fun failuresAndCancellationPreserveExistingImports() = runBlocking {
        val source = wavFixture()
        var output: String? = null
        try {
            val existing = AudioNormalizer().normalizeToWav(source)
            output = existing.wavPath
            val before = imports()
            assertFails { AudioNormalizer().normalizeToWav(source + ".missing") }
            assertFailsWith<IllegalArgumentException> { AudioNormalizer().normalizeToWav(source, 0) }
            val cancelled = async(start = CoroutineStart.LAZY) { AudioNormalizer().normalizeToWav(source) }
            cancelled.cancel()
            assertFailsWith<CancellationException> { cancelled.await() }
            val invalid = NSTemporaryDirectory() + NSUUID().UUIDString + ".wav"
            try {
                write(invalid, byteArrayOf(1, 2, 3, 4))
                assertFails { AudioNormalizer().normalizeToWav(invalid) }
            } finally {
                NSFileManager.defaultManager.removeItemAtPath(invalid, null)
            }
            assertEquals(before, imports())
            assertEquals(existing.contentSha256, sha256Bytes(read(existing.wavPath)))
        } finally {
            listOfNotNull(source, output).forEach { NSFileManager.defaultManager.removeItemAtPath(it, null) }
        }
    }

    private fun imports(): Set<String> = NSFileManager.defaultManager.contentsOfDirectoryAtPath(recordingsDirectoryPath(), null).orEmpty().map { it.toString() }.toSet()

    private fun wavFixture(): String {
        val bytes = ByteArray(44 + 48_000 * 4)
        fun text(offset: Int, value: String) { value.encodeToByteArray().copyInto(bytes, offset) }
        fun number(offset: Int, value: Int, size: Int) { repeat(size) { bytes[offset + it] = (value ushr (8 * it)).toByte() } }
        text(0, "RIFF"); number(4, bytes.size - 8, 4); text(8, "WAVEfmt ")
        number(16, 16, 4); number(20, 1, 2); number(22, 2, 2)
        number(24, 48_000, 4); number(28, 192_000, 4); number(32, 4, 2); number(34, 16, 2)
        text(36, "data"); number(40, bytes.size - 44, 4)
        repeat(48_000) { number(44 + it * 4, 8000, 2); number(46 + it * 4, -8000, 2) }
        return (NSTemporaryDirectory() + NSUUID().UUIDString + ".wav").also { write(it, bytes) }
    }

    private fun write(path: String, bytes: ByteArray) = bytes.usePinned {
        check(NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()).writeToFile(path, true))
    }

    private fun read(path: String): ByteArray {
        val data = NSData.dataWithContentsOfFile(path)!!
        return ByteArray(data.length.toInt()).also { bytes -> bytes.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) } }
    }

    private fun unsigned(bytes: ByteArray, offset: Int, size: Int): Int =
        (0 until size).fold(0) { value, index -> value or ((bytes[offset + index].toInt() and 255) shl (8 * index)) }
}
