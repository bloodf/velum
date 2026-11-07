/*
 * Copyright (c) 2024 Based Hardware Contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package dev.velum.recorder.omi

/**
 * Audio notification framing from Friend.swift `audioCharacteristicUpdated`
 * and docs Protocol.mdx: 2-byte LE packet number + 1-byte fragment index + payload.
 */
data class OmiAudioFragment(
    val packetNumber: Int,
    val fragmentIndex: Int,
    val payload: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OmiAudioFragment) return false
        return packetNumber == other.packetNumber &&
            fragmentIndex == other.fragmentIndex &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = packetNumber
        result = 31 * result + fragmentIndex
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

object OmiAudioPackets {
    fun parseFragment(data: ByteArray): OmiAudioFragment? {
        if (data.size < 3) return null
        val packetNumber = (data[0].toInt() and 0xff) or ((data[1].toInt() and 0xff) shl 8)
        val index = data[2].toInt() and 0xff
        val payload = data.copyOfRange(3, data.size)
        return OmiAudioFragment(packetNumber, index, payload)
    }
}

object OmiLimits {
    /** Upper bound for one reassembled frame (an Opus frame is at most 1275 bytes; BLE payloads are far smaller). */
    const val MAX_FRAME_BYTES: Int = 4096
}

/**
 * Frame reassembly as upstream does it (BasedHardware/omi `app/lib/utils/audio/wav_bytes.dart`, `storeFramePacket`):
 * each notification carries a 16-bit packet index that must advance by exactly one, and a fragment id that is 0 for
 * the first fragment of a frame and then counts up. A gap in either, or a frame that would exceed [maxFrameBytes],
 * drops the pending frame, counts a sync loss in [syncLosses] and waits for the next fragment 0.
 * Deviation from upstream: the 16-bit packet index wraps (65535 then 0) without counting a loss.
 */
class OmiFrameAssembler(private val maxFrameBytes: Int = OmiLimits.MAX_FRAME_BYTES) {
    private var lastPacketIndex = -1
    private var lastFragmentId = -1
    private var pending = ByteArray(0)

    var syncLosses: Long = 0
        private set

    /** The previous frame when [notification] starts a new one in sequence, else null. */
    fun accept(notification: ByteArray): ByteArray? {
        val f = OmiAudioPackets.parseFragment(notification) ?: return null
        if (lastPacketIndex == -1) {
            if (f.fragmentIndex != 0) return null // not in sync yet: wait for a frame start
            start(f)
            return null
        }
        val inSequence = f.packetNumber == (lastPacketIndex + 1) and 0xFFFF &&
            (f.fragmentIndex == 0 || f.fragmentIndex == lastFragmentId + 1)
        if (!inSequence) return lose()
        if (f.fragmentIndex == 0) {
            val done = pending
            start(f)
            return done
        }
        if (pending.size + f.payload.size > maxFrameBytes) return lose()
        pending += f.payload
        lastFragmentId = f.fragmentIndex
        lastPacketIndex = f.packetNumber
        return null
    }

    /** The in-sync trailing frame when the stream ends. */
    fun finish(): ByteArray? {
        val out = pending.takeIf { lastPacketIndex != -1 && it.isNotEmpty() }
        reset()
        return out
    }

    private fun start(f: OmiAudioFragment) {
        pending = if (f.payload.size > maxFrameBytes) { syncLosses++; reset(); return } else f.payload
        lastPacketIndex = f.packetNumber
        lastFragmentId = 0
    }

    private fun lose(): ByteArray? {
        syncLosses++
        reset()
        return null
    }

    private fun reset() {
        lastPacketIndex = -1
        lastFragmentId = -1
        pending = ByteArray(0)
    }
}
