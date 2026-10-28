package dev.velum.ai.audio

import dev.velum.ai.local.ModelStoreDir

/**
 * Turns decrypted Plaud session bodies into self-describing on-disk audio for
 * [dev.velum.ai.pipeline.RecordingProcessor].
 *
 * Note Pro / modern SoftAP decrypt to Ogg Opus (`OggS`). Rare exports are WAV.
 * Everything else (legacy raw PCM16, older Note raw Opus segments, ADPCM, etc.)
 * historically landed as `.bin` and was treated as mono PCM @ 16 kHz by the
 * processor — we now wrap that as a real WAV so streaming decode never sees
 * an opaque extension.
 *
 * All writes go to a same-directory temp file, then rename into place so the
 * destination is never half-written. Download temps use [TEMP_SUFFIX]
 * (`.download.partial`) so mic crash recovery never treats them as mic WAVs.
 */
object DownloadedSessionAudioPersist {
    /** Matches [DeviceCapabilities.sampleRatesHz] for Plaud 1.0 models and the old RP default. */
    const val DEFAULT_SAMPLE_RATE_HZ: Int = 16_000

    /** Matches the pre-#86 RecordingProcessor raw-PCM assumption (mono). */
    const val DEFAULT_CHANNELS: Int = 1

    /** Max bytes appended per call when streaming body/header payloads to the temp file. */
    const val STREAM_CHUNK_BYTES: Int = 256 * 1024

    /**
     * Sibling temp suffix for in-flight download persists.
     * Distinct from mic capture's `.wav.partial` so
     * [dev.velum.app.platform.recoverPartialMicRecordings] never rewrites a crashed download.
     */
    const val TEMP_SUFFIX: String = ".download.partial"

    data class Written(
        val absolutePath: String,
        /** File extension without dot — always `ogg` or `wav` (never `bin`). */
        val extension: String,
        val sizeBytes: Long,
    )

    /** Sniff container kind for extension selection (no allocation of a full WAV). */
    fun detectExtension(decryptedBytes: ByteArray): String = when {
        PcmWav.looksLikeOgg(decryptedBytes) -> "ogg"
        PcmWav.looksLikeWav(decryptedBytes) -> "wav"
        else -> "wav"
    }

    /** True when [fileName] is a download persist temp (current or legacy). */
    fun isDownloadTempName(fileName: String): Boolean {
        val name = fileName.lowercase()
        if (name.endsWith(TEMP_SUFFIX)) return true
        // Pre-#86 download temps used bare `.partial`; Ogg was never a mic capture.
        if (name.endsWith(".ogg.partial")) return true
        return false
    }

    /**
     * Delete leftover download temps under [destDirectory] (crashed / killed mid-persist).
     * Safe at process startup; does not touch mic `.wav.partial` files.
     */
    fun deleteStaleDownloadTemps(destDirectory: ModelStoreDir): Int {
        if (!destDirectory.exists() || !destDirectory.isDirectory()) return 0
        var removed = 0
        for (child in destDirectory.listChildren()) {
            if (child.isDirectory()) continue
            if (!isDownloadTempName(child.name)) continue
            runCatching { child.deleteRecursively() }
            if (!child.exists()) removed++
        }
        return removed
    }

    /**
     * Stream [decryptedBytes] into [destDirectory]/[fileBaseName].{ogg|wav} via a temp file
     * and atomic rename. Never builds a full WAV copy of opaque PCM on the heap.
     */
    fun persistToDirectory(
        decryptedBytes: ByteArray,
        destDirectory: ModelStoreDir,
        fileBaseName: String,
        sampleRateHz: Int = DEFAULT_SAMPLE_RATE_HZ,
        channels: Int = DEFAULT_CHANNELS,
        chunkBytes: Int = STREAM_CHUNK_BYTES,
        logInfo: (String) -> Unit = { },
    ): Written {
        require(chunkBytes > 0) { "chunkBytes must be positive" }
        val extension = detectExtension(decryptedBytes)
        destDirectory.createDirectories()
        val dest = destDirectory.resolve("$fileBaseName.$extension")
        val rate = sampleRateHz.coerceAtLeast(1)
        val ch = channels.coerceIn(1, 2)
        writeAtomically(dest) { temp ->
            when (extension) {
                "ogg" -> streamBytesToFile(temp, decryptedBytes, chunkBytes)
                "wav" -> {
                    if (PcmWav.looksLikeWav(decryptedBytes)) {
                        streamBytesToFile(temp, decryptedBytes, chunkBytes)
                    } else {
                        // TODO(#95): replace PCM wrap with real decode
                        logInfo(
                            "audio-persist: wrapping opaque body as PCM WAV " +
                                "(sizeBytes=${decryptedBytes.size}, sniff=none, " +
                                "sampleRateHz=$rate, channels=$ch)",
                        )
                        streamOpaquePcmAsWav(
                            temp = temp,
                            pcm = decryptedBytes,
                            sampleRateHz = rate,
                            channels = ch,
                            chunkBytes = chunkBytes,
                        )
                    }
                }
                else -> error("unexpected audio extension: $extension")
            }
        }
        return Written(
            absolutePath = dest.absolutePath,
            extension = extension,
            sizeBytes = dest.length(),
        )
    }

    /**
     * Write [writeTempContents] into a sibling [TEMP_SUFFIX] file, then rename onto [dest].
     * On any failure the temp is deleted and [dest] is left untouched (never half-written).
     */
    internal fun writeAtomically(
        dest: ModelStoreDir,
        rename: (from: ModelStoreDir, to: ModelStoreDir) -> Boolean = { from, to -> from.renameTo(to) },
        writeTempContents: (temp: ModelStoreDir) -> Unit,
    ): Long {
        val temp = ModelStoreDir("${dest.absolutePath}$TEMP_SUFFIX")
        try {
            if (temp.exists()) temp.deleteRecursively()
            // Parent of dest (and temp) must exist before create/rename.
            ModelStoreDir(
                dest.absolutePath.substringBeforeLast('/', missingDelimiterValue = dest.absolutePath),
            ).createDirectories()
            writeTempContents(temp)
            // Publish only after the temp is fully written — rename replaces dest atomically
            // when the platform supports it (same directory).
            check(rename(temp, dest)) {
                "atomic rename failed: ${temp.absolutePath} → ${dest.absolutePath}"
            }
            return dest.length()
        } catch (t: Throwable) {
            runCatching { if (temp.exists()) temp.deleteRecursively() }
            throw t
        }
    }

    private fun streamOpaquePcmAsWav(
        temp: ModelStoreDir,
        pcm: ByteArray,
        sampleRateHz: Int,
        channels: Int,
        chunkBytes: Int,
    ) {
        val header = PcmWav.header(
            dataSize = pcm.size,
            sampleRateHz = sampleRateHz,
            channels = channels,
        )
        temp.writeBytes(header)
        appendBytesChunked(temp, pcm, chunkBytes)
    }

    private fun streamBytesToFile(temp: ModelStoreDir, bytes: ByteArray, chunkBytes: Int) {
        if (bytes.isEmpty()) {
            temp.writeBytes(ByteArray(0))
            return
        }
        val first = bytes.size.coerceAtMost(chunkBytes)
        temp.writeBytes(bytes.copyOfRange(0, first))
        if (first < bytes.size) {
            appendBytesChunked(temp, bytes, chunkBytes, start = first)
        }
    }

    private fun appendBytesChunked(
        dest: ModelStoreDir,
        bytes: ByteArray,
        chunkBytes: Int,
        start: Int = 0,
    ) {
        var offset = start
        while (offset < bytes.size) {
            val end = (offset + chunkBytes).coerceAtMost(bytes.size)
            dest.appendBytes(bytes.copyOfRange(offset, end))
            offset = end
        }
    }
}
