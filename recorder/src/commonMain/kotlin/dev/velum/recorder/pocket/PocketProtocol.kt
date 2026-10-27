/*
 * Copyright (c) 2026 Shah Baig
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
package dev.velum.recorder.pocket

/**
 * Pocket AI BLE constants and APP&/MCU& framing from pocket-libre
 * `protocol.py` / `commands.py`.
 */
object PocketUuids {
    const val DEVICE_NAME_PREFIX = "PKT01"
    val ADVERTISED_SERVICES = listOf("5536", "2222")

    const val BATTERY_SERVICE = "0000180f-0000-1000-8000-00805f9b34fb"
    const val BATTERY_LEVEL = "00002a19-0000-1000-8000-00805f9b34fb"

    const val AUDIO_SERVICE = "001120a0-2233-4455-6677-889912345678"
    const val AUDIO_WRITE = "001120a2-2233-4455-6677-889912345678"
    const val AUDIO_DATA = "001120a1-2233-4455-6677-889912345678"
    const val AUDIO_META = "001120a3-2233-4455-6677-889912345678"

    const val CMD_WRITE = AUDIO_META
    const val CMD_NOTIFY = AUDIO_META
    const val AUDIO_NOTIFY = AUDIO_DATA
}

object PocketProtocol {
    const val CMD_PREFIX = "APP&"
    const val RSP_PREFIX = "MCU&"
    const val BYTES_PER_SECOND = 4000
    val MP3_FRAME_HEADER = byteArrayOf(0xff.toByte(), 0xf3.toByte(), 0x48, 0xc4.toByte())

    fun authenticate(sessionKey: String): String = "${CMD_PREFIX}SK&$sessionKey"

    fun listDirs(): String = "${CMD_PREFIX}LIST&"

    fun listFiles(date: String): String = "${CMD_PREFIX}LIST&$date"

    fun download(date: String, timestamp: String): String = "${CMD_PREFIX}U&$date&$timestamp"

    fun wifiOn(): String = "${CMD_PREFIX}WIFIO"

    fun wifiOff(): String = "${CMD_PREFIX}WIFIC"

    fun splitMessages(text: String): List<String> {
        val cleaned = text.replace("\u0000", "").trim()
        if (cleaned.isEmpty()) return emptyList()
        val parts = Regex("(?=MCU&)").split(cleaned)
        return parts.map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun parseSkResponse(message: String): Boolean = when {
        message.startsWith("${RSP_PREFIX}SK&OK") -> true
        message.startsWith("${RSP_PREFIX}SK&ERR") -> false
        else -> false
    }

    data class ListedRecording(val date: String, val timestamp: String, val durationS: Int) {
        val estimatedBytes: Int get() = maxOf(durationS, 0) * BYTES_PER_SECOND
    }

    /**
     * MCU&F&\<date\>&\<ts\>&\<duration_s\> style list rows (see PROTOCOL.md).
     */
    fun parseListRow(message: String): ListedRecording? {
        if (!message.startsWith("${RSP_PREFIX}F&")) return null
        val body = message.removePrefix("${RSP_PREFIX}F&")
        val parts = body.split('&')
        if (parts.size < 3) return null
        val duration = parts[2].toIntOrNull() ?: return null
        return ListedRecording(date = parts[0], timestamp = parts[1], durationS = duration)
    }
}
