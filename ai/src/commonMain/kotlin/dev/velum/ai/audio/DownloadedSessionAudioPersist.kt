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
 * In-flight writes land under [DOWNLOAD_TEMP_DIR_NAME] with [TEMP_SUFFIX], then
 * rename into the recordings directory so mic crash recovery never sees them.
 */
object DownloadedSessionAudioPersist {
    /** Matches [DeviceCapabilities.sampleRatesHz] for Plaud 1.0 models and the old RP default. */
    const val DEFAULT_SAMPLE_RATE_HZ: Int = 16_000

    /** Matches the pre-#86 RecordingProcessor raw-PCM assumption (mono). */
    const val DEFAULT_CHANNELS: Int = 1

    /** Max bytes appended per call when streaming body/header payloads to the temp file. */
    const val STREAM_CHUNK_BYTES: Int = 256 * 1024

    /**
     * Sibling directory under the recordings root for in-flight download persists.
     * Wiped wholesale at startup — never scanned by mic recovery.
     */
    const val DOWNLOAD_TEMP_DIR_NAME: String = ".download-tmp"

    /**
     * Temp file suffix inside [DOWNLOAD_TEMP_DIR_NAME].
     * Distinct from mic capture's `.wav.partial`.
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

    fun downloadTempDirectory(recordingsDirectory: ModelStoreDir): ModelStoreDir =
        recordingsDirectory.resolve(DOWNLOAD_TEMP_DIR_NAME)

    /**
     * True when [fileName] is a download persist temp (current or legacy) that must never
     * be treated as a mic `.wav.partial`. Mic captures are `rec-*.wav.partial` only.
     */
    fun isDownloadTempName(fileName: String): Boolean {
        val name = fileName.lowercase()
        if (name.endsWith(TEMP_SUFFIX)) return true
        // Pre-#86 download temps used bare `.partial`; Ogg was never a mic capture.
        if (name.endsWith(".ogg.partial")) return true
        // Stray `x.wav.partial` (no rec- prefix) is never a phone-mic capture.
        if (name.endsWith(".wav.partial") && !name.startsWith("rec-")) return true
        return false
    }

    /**
     * Delete leftover download temps under [recordingsDirectory] and its
     * [DOWNLOAD_TEMP_DIR_NAME] child. Does not touch live mic `rec-*.wav.partial`.
     */
    fun deleteStaleDownloadTemps(recordingsDirectory: ModelStoreDir): Int {
        if (!recordingsDirectory.exists() || !recordingsDirectory.isDirectory()) return 0
        var removed = 0
        val tempDir = downloadTempDirectory(recordingsDirectory)
        if (tempDir.exists()) {
            for (child in tempDir.listChildren()) {
                if (child.isDirectory()) continue
                runCatching { child.deleteRecursively() }
                if (!child.exists()) removed++
            }
        }
        for (child in recordingsDirectory.listChildren()) {
            if (child.isDirectory()) continue
            if (!isDownloadTempName(child.name)) continue
            runCatching { child.deleteRecursively() }
            if (!child.exists()) removed++
        }
        return removed
    }

    /**
     * Stream [decryptedBytes] into [destDirectory]/[fileBaseName].{ogg|wav} via a temp file
     * under [DOWNLOAD_TEMP_DIR_NAME] and atomic rename. Never builds a full WAV copy of opaque
     * PCM on the heap.
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
        writeAtomically(destDirectory, dest) { temp ->
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
     * Write [writeTempContents] into [DOWNLOAD_TEMP_DIR_NAME]/[dest].name[TEMP_SUFFIX], then
     * rename onto [dest]. On any failure the temp is deleted and [dest] is left untouched.
     */
    internal fun writeAtomically(
        recordingsDirectory: ModelStoreDir,
        dest: ModelStoreDir,
        rename: (from: ModelStoreDir, to: ModelStoreDir) -> Boolean = { from, to -> from.renameTo(to) },
        writeTempContents: (temp: ModelStoreDir) -> Unit,
    ): Long {
        recordingsDirectory.createDirectories()
        val tempDir = downloadTempDirectory(recordingsDirectory)
        tempDir.createDirectories()
        val temp = tempDir.resolve("${dest.name}$TEMP_SUFFIX")
        try {
            if (temp.exists()) temp.deleteRecursively()
            writeTempContents(temp)
            check(rename(temp, dest)) {
                "atomic rename failed: ${temp.absolutePath} → ${dest.absolutePath}"
            }
            return dest.length()
        } catch (t: Throwable) {
            runCatching { if (temp.exists()) temp.deleteRecursively() }
            throw t
        }
    }

    /** Convenience for tests that already have a dest under a recordings directory. */
    internal fun writeAtomically(
        dest: ModelStoreDir,
        rename: (from: ModelStoreDir, to: ModelStoreDir) -> Boolean = { from, to -> from.renameTo(to) },
        writeTempContents: (temp: ModelStoreDir) -> Unit,
    ): Long {
        val parentPath = dest.absolutePath.substringBeforeLast('/', missingDelimiterValue = dest.absolutePath)
        return writeAtomically(
            recordingsDirectory = ModelStoreDir(parentPath),
            dest = dest,
            rename = rename,
            writeTempContents = writeTempContents,
        )
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
