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

/**
 * Reassembles fragments the way Friend.swift does: on fragment index 0, flush
 * the previous packet; append payload bytes to the current packet number.
 */
class OmiPacketReassembler {
    private val counter = BleAudioPacketCounter()
    private var currentNumber: Int? = null
    private val current = ArrayList<Byte>()

    /**
     * @return completed packet payload when a new packet (index 0) starts after
     * a previous one, or null while still accumulating.
     */
    fun push(fragment: OmiAudioFragment): ByteArray? {
        val notification = ByteArray(3 + fragment.payload.size)
        notification[0] = (fragment.packetNumber and 0xff).toByte()
        notification[1] = ((fragment.packetNumber ushr 8) and 0xff).toByte()
        notification[2] = (fragment.fragmentIndex and 0xff).toByte()
        fragment.payload.copyInto(notification, 3)
        counter.record(notification)

        var flushed: ByteArray? = null
        if (fragment.fragmentIndex == 0) {
            if (current.isNotEmpty()) {
                flushed = current.toByteArray()
                current.clear()
            }
            currentNumber = fragment.packetNumber
        }
        if (currentNumber == null) {
            currentNumber = fragment.packetNumber
        }
        current.addAll(fragment.payload.toList())
        return flushed
    }

    fun flush(): ByteArray? {
        if (current.isEmpty()) return null
        val out = current.toByteArray()
        current.clear()
        currentNumber = null
        return out
    }

    fun stats(): Pair<Long, Long> = counter.snapshot()
}
