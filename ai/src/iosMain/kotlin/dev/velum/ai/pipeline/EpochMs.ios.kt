package dev.velum.ai.pipeline

import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

actual fun epochMsNow(): Long = (NSDate().timeIntervalSince1970 * 1000.0).toLong()
