@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.velum.ai.audio

import dev.velum.ai.local.ModelStoreDir
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.AudioToolbox.AudioConverterDispose
import platform.AudioToolbox.AudioConverterFillComplexBuffer
import platform.AudioToolbox.AudioConverterNew
import platform.AudioToolbox.AudioConverterRef
import platform.AudioToolbox.AudioConverterRefVar
import platform.CoreAudioTypes.AudioBufferList
import platform.CoreAudioTypes.AudioStreamBasicDescription
import platform.CoreAudioTypes.AudioStreamPacketDescription
import platform.CoreAudioTypes.kAudioFormatFlagIsPacked
import platform.CoreAudioTypes.kAudioFormatFlagIsSignedInteger
import platform.CoreAudioTypes.kAudioFormatLinearPCM
import platform.CoreAudioTypes.kAudioFormatOpus
import platform.posix.EINTR
import platform.posix.close
import platform.posix.errno
import platform.posix.mkstemp
import platform.posix.rename
import platform.posix.unlink
import platform.posix.write
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.free
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.memcpy
import platform.posix.realpath

actual fun createOpusDecoder(): OpusDecoder = AppleOpusDecoder()

private class AppleOpusDecoder : OpusDecoder {
    override fun decodeToPcm(oggBytes: ByteArray): PcmAudio {
        val chunks = ArrayList<ShortArray>()
        var count = 0
        val meta = decode({ header, packet -> OggOpusDemuxer.demuxForEach(oggBytes, header, packet) }) { bytes ->
            val samples = ShortArray(bytes.size / 2) {
                ((bytes[it * 2].toInt() and 255) or (bytes[it * 2 + 1].toInt() shl 8)).toShort()
            }
            check(count <= Int.MAX_VALUE - samples.size) { "Decoded PCM is too large for memory" }
            count += samples.size
            chunks.add(samples)
        }
        val samples = ShortArray(count)
        var offset = 0
        for (chunk in chunks) {
            chunk.copyInto(samples, offset)
            offset += chunk.size
        }
        return PcmAudio(samples, DECODED_PCM_RATE_HZ, DECODED_PCM_CHANNELS, meta.truncated)
    }

    override fun decodeToPcmFile(oggBytes: ByteArray, dest: ModelStoreDir): SpilledPcmMeta =
        spill(dest) { header, packet -> OggOpusDemuxer.demuxForEach(oggBytes, header, packet) }

    override fun decodePathToPcmFile(path: String, dest: ModelStoreDir, checkCancelled: () -> Unit): SpilledPcmMeta {
        require(path != dest.absolutePath) { "PCM destination must differ from the Ogg source" }
        val sourcePath = realpath(path, null)
        val destinationPath = realpath(dest.absolutePath, null)
        try {
            require(sourcePath == null || destinationPath == null || sourcePath.toKString() != destinationPath.toKString()) {
                "PCM destination must differ from the Ogg source"
            }
        } finally {
            free(sourcePath)
            free(destinationPath)
        }
        val file = fopen(path, "rb") ?: error("Cannot open Ogg source")
        try {
            check(fseek(file, 0, SEEK_END) == 0) { "Cannot seek Ogg source" }
            val size = ftell(file)
            check(size >= 0) { "Cannot measure Ogg source" }
            return spill(dest) { header, packet ->
                OggOpusDemuxer.demuxAtForEach(
                    size,
                    { buffer, position, length ->
                        checkCancelled()
                        check(fseek(file, position, SEEK_SET) == 0) { "Cannot seek Ogg source" }
                        if (length == 0) 0 else buffer.usePinned {
                            fread(it.addressOf(0), 1u, length.toULong(), file).toInt()
                        }
                    },
                    header,
                    { checkCancelled(); packet(it) },
                )
            }
        } finally {
            fclose(file)
        }
    }

    private fun spill(
        dest: ModelStoreDir,
        demux: ((Int, Int, Int) -> Unit, (ByteArray) -> Unit) -> OpusStreamMeta,
    ): SpilledPcmMeta {
        val parent = dest.absolutePath.substringBeforeLast('/', "")
        if (parent.isNotEmpty()) ModelStoreDir(parent).createDirectories()
        val template = (dest.absolutePath + ".opus-XXXXXX").encodeToByteArray() + byteArrayOf(0)
        var fd = template.usePinned { mkstemp(it.addressOf(0)) }
        check(fd >= 0) { "Cannot create PCM spill: errno $errno" }
        val temporaryPath = template.usePinned { it.addressOf(0).toKString() }
        var published = false
        var failure: Throwable? = null
        try {
            var size = 0L
            val meta = decode(demux) { bytes ->
                bytes.usePinned { pinned ->
                    var offset = 0
                    while (offset < bytes.size) {
                        val written = write(fd, pinned.addressOf(offset), (bytes.size - offset).toULong())
                        val writeError = errno
                        if (written < 0 && writeError == EINTR) continue
                        check(written > 0) { "Cannot write PCM spill: errno $writeError, wrote $written" }
                        offset += written.toInt()
                    }
                }
                size += bytes.size
            }
            val outputFd = fd
            fd = -1 // Never retry close after an error: the descriptor may already have been released.
            check(close(outputFd) == 0) { "Cannot close PCM spill: errno $errno" }
            check(rename(temporaryPath, dest.absolutePath) == 0) { "Cannot publish PCM spill: errno $errno" }
            published = true
            return SpilledPcmMeta(DECODED_PCM_RATE_HZ, DECODED_PCM_CHANNELS, size, meta.truncated)
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            // Only the exclusively created temporary file is ours to remove on failure.
            val cleanup = runCatching {
                val closeResult = if (fd >= 0) close(fd) else 0
                val closeError = errno
                val removeResult = if (!published) unlink(temporaryPath) else 0
                val removeError = errno
                check(closeResult == 0 && removeResult == 0) {
                    "Cannot clean PCM spill: close=$closeResult errno=$closeError, unlink=$removeResult errno=$removeError"
                }
            }.exceptionOrNull()
            if (cleanup != null) {
                val original = failure
                if (original != null) original.addSuppressed(cleanup) else throw cleanup
            }
        }
    }

    private fun decode(
        demux: ((Int, Int, Int) -> Unit, (ByteArray) -> Unit) -> OpusStreamMeta,
        emit: (ByteArray) -> Unit,
    ): OpusStreamMeta {
        var converter: AppleOpusPackets? = null
        var skip = 0
        val output: (ByteArray) -> Unit = { bytes ->
            val drop = minOf(skip, bytes.size / 2)
            skip -= drop
            if (drop * 2 < bytes.size) emit(bytes.copyOfRange(drop * 2, bytes.size))
        }
        try {
            val meta = demux(
                { _, channels, preSkip ->
                    check(converter == null) { "Multiple Opus headers" }
                    skip = preSkip / 3
                    converter = AppleOpusPackets(channels)
                },
                { packet -> requireNotNull(converter).convert(packet, output) },
            )
            converter?.convert(null, output)
            return meta
        } finally {
            converter?.close()
        }
    }
}

// AudioConverter owns codec state; each recording gets a fresh instance.
private class AppleOpusPackets(private val channels: Int) {
    private val converter: AudioConverterRef
    private var packet: ByteArray? = null
    private var supplied = false
    private val description = nativeHeap.alloc<AudioStreamPacketDescription>()
    private var input: CPointer<ByteVar>? = null
    private var ended = false

    init {
        try {
            converter = memScoped {
                val source = alloc<AudioStreamBasicDescription>()
                source.mSampleRate = 48_000.0
                source.mFormatID = kAudioFormatOpus
                source.mChannelsPerFrame = channels.toUInt()
                val target = alloc<AudioStreamBasicDescription>()
                target.mSampleRate = DECODED_PCM_RATE_HZ.toDouble()
                target.mFormatID = kAudioFormatLinearPCM
                target.mFormatFlags = kAudioFormatFlagIsSignedInteger or kAudioFormatFlagIsPacked
                target.mChannelsPerFrame = 1u
                target.mBitsPerChannel = 16u
                target.mFramesPerPacket = 1u
                target.mBytesPerFrame = 2u
                target.mBytesPerPacket = 2u
                val result = alloc<AudioConverterRefVar>()
                val status = AudioConverterNew(source.ptr, target.ptr, result.ptr)
                check(status == 0) { "Apple Opus decoder unavailable: OSStatus $status" }
                requireNotNull(result.value)
            }
        } catch (failure: Throwable) {
            nativeHeap.free(description)
            throw failure
        }
    }

    fun convert(bytes: ByteArray?, emit: (ByteArray) -> Unit) {
        require(bytes == null || bytes.isNotEmpty()) { "Empty Opus packet" }
        check(!ended) { "Opus stream already ended" }
        input?.let { nativeHeap.free(it) }
        input = bytes?.let { data ->
            nativeHeap.allocArray<ByteVar>(data.size).also { pointer ->
                data.usePinned { memcpy(pointer, it.addressOf(0), data.size.toULong()) }
            }
        }
        packet = bytes
        supplied = false
        ended = bytes == null
        val reference = StableRef.create(this)
        try {
            memScoped {
                val output = allocArray<ByteVar>(3840)
                val buffers = alloc<AudioBufferList>()
                val frames = alloc<UIntVar>()
                do {
                    frames.value = 1920u // Maximum Opus packet duration is 120 ms.
                    buffers.mNumberBuffers = 1u
                    buffers.mBuffers[0].mNumberChannels = 1u
                    buffers.mBuffers[0].mDataByteSize = 3840u
                    buffers.mBuffers[0].mData = output
                    val status = AudioConverterFillComplexBuffer(
                        converter,
                        staticCFunction { _, count, data, descriptions, context ->
                            val state = context!!.asStableRef<AppleOpusPackets>().get()
                            if (state.supplied || state.packet == null) {
                                count!!.pointed.value = 0u
                                data!!.pointed.mBuffers[0].mDataByteSize = 0u
                                if (state.ended) 0 else NEED_INPUT
                            } else {
                                state.supplied = true
                                count!!.pointed.value = 1u
                                data!!.pointed.mNumberBuffers = 1u
                                data.pointed.mBuffers[0].mNumberChannels = state.channels.toUInt()
                                data.pointed.mBuffers[0].mDataByteSize = state.packet!!.size.toUInt()
                                data.pointed.mBuffers[0].mData = state.input
                                state.description.mStartOffset = 0
                                state.description.mVariableFramesInPacket = 0u
                                state.description.mDataByteSize = state.packet!!.size.toUInt()
                                descriptions?.pointed?.value = state.description.ptr
                                0
                            }
                        },
                        reference.asCPointer(), frames.ptr, buffers.ptr, null,
                    )
                    check(status == 0 || status == NEED_INPUT) { "Apple Opus decode failed: OSStatus $status" }
                    check(frames.value <= 1920u) { "Invalid Apple PCM frame count" }
                    if (frames.value > 0u) emit(output.readBytes(frames.value.toInt() * 2))
                } while (frames.value > 0u)
            }
        } finally {
            reference.dispose()
        }
    }

    fun close() {
        AudioConverterDispose(converter)
        input?.let { nativeHeap.free(it) }
        nativeHeap.free(description)
    }

    companion object {
        private const val NEED_INPUT = -70001
    }
}
