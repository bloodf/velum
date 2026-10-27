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
 * Friend/Omi codec byte values from `Friend.swift` FriendCodec /
 * `bt_device.dart` BleAudioCodec.
 */
enum class OmiCodec(val code: Int, val sampleRateHz: Int) {
    Pcm16(0, 16_000),
    Pcm8(1, 8_000),
    MuLaw16(10, 16_000),
    MuLaw8(11, 8_000),
    Opus16(20, 16_000),
    ;

    companion object {
        fun fromByte(value: Int): OmiCodec? =
            entries.firstOrNull { it.code == (value and 0xff) }
    }
}
