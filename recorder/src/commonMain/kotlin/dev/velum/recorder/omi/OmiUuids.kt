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

/** GATT UUIDs from BasedHardware/omi `app/lib/services/devices/models.dart`. */
object OmiUuids {
    const val SERVICE = "19b10000-e8f2-537e-4f6c-d104768a1214"
    const val AUDIO_DATA = "19b10001-e8f2-537e-4f6c-d104768a1214"
    const val AUDIO_CODEC = "19b10002-e8f2-537e-4f6c-d104768a1214"
    const val LIGHT_CODEC = "19b10003-e8f2-537e-4f6c-d104768a1214"

    const val BATTERY_SERVICE = "0000180f-0000-1000-8000-00805f9b34fb"
    const val BATTERY_LEVEL = "00002a19-0000-1000-8000-00805f9b34fb"

    const val DEVICE_INFORMATION = "0000180a-0000-1000-8000-00805f9b34fb"
    const val MODEL_NUMBER = "00002a24-0000-1000-8000-00805f9b34fb"
    const val FIRMWARE_REVISION = "00002a26-0000-1000-8000-00805f9b34fb"
    const val HARDWARE_REVISION = "00002a27-0000-1000-8000-00805f9b34fb"
    const val MANUFACTURER_NAME = "00002a29-0000-1000-8000-00805f9b34fb"

    const val PLAUD_SERVICE = "00001910-0000-1000-8000-00805f9b34fb"
    const val BEE_SERVICE = "03d5d5c4-a86c-11ee-9d89-8f2089a49e7e"
    const val BEE_AUDIO = "b189a505-a86c-11ee-a5fb-8f2089a49e7e"
    const val LIMITLESS_SERVICE = "632de001-604c-446b-a80f-7963e950f3fb"
    const val LIMITLESS_TX = "632de002-604c-446b-a80f-7963e950f3fb"
    const val LIMITLESS_RX = "632de003-604c-446b-a80f-7963e950f3fb"
}
