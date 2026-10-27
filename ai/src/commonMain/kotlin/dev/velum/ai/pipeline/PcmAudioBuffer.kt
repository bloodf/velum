package dev.velum.ai.pipeline

import dev.velum.ai.local.ModelStoreDir

/**
 * PCM (16-bit LE) held in memory for short clips, or spilled to a temp file when larger
 * than [IN_MEMORY_BUDGET_BYTES] so long recordings never keep a multi-hundred-MiB heap array.
 */
sealed class PcmAudioBuffer {
    abstract val sizeBytes: Long
    abstract fun slice(startByte: Int, endByte: Int): ByteArray
    abstract fun asFullArrayOrNull(): ByteArray?
    abstract fun close()

    class Memory(private val bytes: ByteArray) : PcmAudioBuffer() {
        override val sizeBytes: Long get() = bytes.size.toLong()
        override fun slice(startByte: Int, endByte: Int): ByteArray =
            bytes.copyOfRange(startByte, endByte)
        override fun asFullArrayOrNull(): ByteArray = bytes
        override fun close() = Unit
    }

    class Spilled(
        private val file: ModelStoreDir,
        override val sizeBytes: Long,
    ) : PcmAudioBuffer() {
        override fun slice(startByte: Int, endByte: Int): ByteArray {
            require(startByte in 0..endByte) { "bad range $startByte..$endByte" }
            require(endByte <= sizeBytes) { "end $endByte > size $sizeBytes" }
            return file.readRange(startByte.toLong(), endByte - startByte)
        }

        override fun asFullArrayOrNull(): ByteArray? = null

        override fun close() {
            runCatching { file.deleteRecursively() }
        }
    }

    companion object {
        /** Keep PCM on the heap only up to ~48 MiB (~25 min mono 16 kHz). */
        const val IN_MEMORY_BUDGET_BYTES: Long = 48L * 1024L * 1024L

        /**
         * Absolute ceiling for a single recording's PCM on disk (~12 h mono 16 kHz).
         * Above this we fail — not for heap, but to bound temp storage.
         */
        const val MAX_SPILL_BYTES: Long = 1_500_000_000L

        fun fromPcmBytes(
            pcm: ByteArray,
            spillDir: ModelStoreDir,
            inMemoryBudget: Long = IN_MEMORY_BUDGET_BYTES,
        ): PcmAudioBuffer {
            require(pcm.size.toLong() <= MAX_SPILL_BYTES) {
                "PCM ${pcm.size} bytes exceeds max spill $MAX_SPILL_BYTES"
            }
            if (pcm.size.toLong() <= inMemoryBudget) {
                return Memory(pcm)
            }
            spillDir.createDirectories()
            val file = spillDir.resolve("pcm-${pcm.size}.s16le")
            file.writeBytes(pcm)
            return Spilled(file, pcm.size.toLong())
        }
    }
}
