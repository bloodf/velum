package dev.velum.core.data

import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

actual fun epochSecondsNow(): Long = NSDate().timeIntervalSince1970.toLong()
