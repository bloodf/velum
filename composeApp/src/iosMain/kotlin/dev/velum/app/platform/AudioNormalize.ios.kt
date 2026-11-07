@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.velum.app.platform

import kotlinx.cinterop.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import platform.AudioToolbox.*
import platform.CoreAudioTypes.*
import platform.Foundation.NSFileManager
import platform.CoreFoundation.CFURLCreateFromFileSystemRepresentation
import platform.CoreFoundation.CFRelease
import platform.Foundation.NSUUID
import platform.posix.*
import kotlin.coroutines.coroutineContext

actual class AudioNormalizer actual constructor() {
    actual suspend fun normalizeToWav(sourcePath: String, targetSampleRateHz: Int): NormalizedAudio {
        var ownedPath: String? = null
        try {
            return withContext(Dispatchers.Default) {
                require(targetSampleRateHz in 1..192_000) { "Invalid target sample rate: $targetSampleRateHz" }
                coroutineContext.ensureActive()
                val context = coroutineContext
                val directory = recordingsDirectoryPath()
                check(NSFileManager.defaultManager.createDirectoryAtPath(directory, true, null, null)) {
                    "Cannot create recordings directory"
                }
                val output = "$directory/import_${NSUUID().UUIDString}.wav"
                val temporary = "$output.part"
                ownedPath = temporary
                try {
                    val frames = convertImport(sourcePath, temporary, targetSampleRateHz) { context.ensureActive() }
                    val hash = sha256File(temporary)
                    context.ensureActive()
                    check(rename(temporary, output) == 0) { "Cannot publish normalized audio" }
                    ownedPath = output
                    context.ensureActive()
                    NormalizedAudio(output, targetSampleRateHz, frames * 1000 / targetSampleRateHz, null, hash)
                } catch (failure: Throwable) {
                    ownedPath?.let { unlink(it) }
                    throw failure
                }
            }
        } catch (cancelled: CancellationException) {
            // withContext can discard a published result when cancellation wins dispatch back to the caller.
            ownedPath?.let { unlink(it) }
            throw cancelled
        }
    }
}

private fun audioStatus(status: Int, operation: String) {
    check(status == 0) { "$operation failed (OSStatus $status)" }
}

private fun convertImport(source: String, output: String, rate: Int, checkCancelled: () -> Unit): Long = memScoped {
    val input = alloc<ExtAudioFileRefVar>()
    input.value = null
    val pathBytes = source.encodeToByteArray()
    require(pathBytes.isNotEmpty() && !source.contains('\u0000')) { "Invalid import path" }
    val url = pathBytes.usePinned {
        CFURLCreateFromFileSystemRepresentation(null, it.addressOf(0).reinterpret(), pathBytes.size.toLong(), false)
    } ?: error("Invalid import URL")
    try {
        audioStatus(ExtAudioFileOpenURL(url, input.ptr), "Open imported audio")
    } finally {
        CFRelease(url)
    }
    val file = input.value ?: error("No imported audio file")
    try {
        val format = alloc<AudioStreamBasicDescription>()
        format.mSampleRate = rate.toDouble()
        format.mFormatID = kAudioFormatLinearPCM
        format.mFormatFlags = kAudioFormatFlagIsSignedInteger or kAudioFormatFlagIsPacked
        format.mBytesPerPacket = 2u
        format.mFramesPerPacket = 1u
        format.mBytesPerFrame = 2u
        format.mChannelsPerFrame = 1u
        format.mBitsPerChannel = 16u
        format.mReserved = 0u
        audioStatus(
            ExtAudioFileSetProperty(file, kExtAudioFileProperty_ClientDataFormat, sizeOf<AudioStreamBasicDescription>().toUInt(), format.ptr),
            "Configure PCM16 mono conversion",
        )
        val destination = fopen(output, "wb") ?: error("Cannot create normalized WAV")
        var closed = false
        try {
            val header = importWavHeader(rate, 0)
            header.usePinned { check(fwrite(it.addressOf(0), 1u, header.size.toULong(), destination) == header.size.toULong()) }
            val samples = allocArray<ShortVar>(4096)
            val buffers = alloc<AudioBufferList>()
            buffers.mNumberBuffers = 1u
            val count = alloc<UIntVar>()
            var total = 0L
            while (true) {
                checkCancelled()
                count.value = 4096u
                buffers.mBuffers[0].mNumberChannels = 1u
                buffers.mBuffers[0].mDataByteSize = 8192u
                buffers.mBuffers[0].mData = samples
                audioStatus(ExtAudioFileRead(file, count.ptr, buffers.ptr), "Decode imported audio")
                if (count.value == 0u) break
                total += count.value.toLong()
                check(total <= (UInt.MAX_VALUE.toLong() - 36) / 2) { "Normalized audio exceeds WAV size limit" }
                val bytes = count.value.toULong() * 2u
                check(fwrite(samples, 1u, bytes, destination) == bytes) { "Cannot write normalized audio" }
            }
            require(total > 0) { "Imported audio contains no frames" }
            check(fseek(destination, 0, SEEK_SET) == 0) { "Cannot seek normalized WAV" }
            val finalHeader = importWavHeader(rate, total * 2)
            finalHeader.usePinned { check(fwrite(it.addressOf(0), 1u, 44u, destination) == 44uL) }
            check(fflush(destination) == 0 && fsync(fileno(destination)) == 0) { "Cannot flush normalized WAV" }
            val closeStatus = fclose(destination)
            closed = true
            check(closeStatus == 0) { "Cannot close normalized WAV" }
            total
        } finally {
            if (!closed) fclose(destination)
        }
    } finally {
        ExtAudioFileDispose(file)
    }
}

private fun importWavHeader(rate: Int, bytes: Long): ByteArray = ByteArray(44).apply {
    fun text(offset: Int, value: String) { value.encodeToByteArray().copyInto(this, offset) }
    fun number(offset: Int, value: Long, size: Int) {
        repeat(size) { this[offset + it] = (value ushr (it * 8)).toByte() }
    }
    text(0, "RIFF"); number(4, bytes + 36, 4); text(8, "WAVEfmt ")
    number(16, 16, 4); number(20, 1, 2); number(22, 1, 2)
    number(24, rate.toLong(), 4); number(28, rate.toLong() * 2, 4)
    number(32, 2, 2); number(34, 16, 2); text(36, "data"); number(40, bytes, 4)
}
