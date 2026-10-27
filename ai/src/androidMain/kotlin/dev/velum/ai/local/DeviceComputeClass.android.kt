package dev.velum.ai.local

import android.app.ActivityManager
import android.content.Context
import java.util.concurrent.atomic.AtomicReference

private val appContextRef = AtomicReference<Context?>(null)

fun installDeviceComputeContext(context: Context) {
    appContextRef.set(context.applicationContext)
}

actual fun detectDeviceComputeClass(): DeviceComputeClass {
    val context = appContextRef.get() ?: return DeviceComputeClass.Mid
    return runCatching {
        val am = context.getSystemService(ActivityManager::class.java) ?: return DeviceComputeClass.Mid
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        val totalMb = info.totalMem / (1024L * 1024L)
        val classMb = am.memoryClass
        when {
            totalMb > 0 && totalMb < 3200L -> DeviceComputeClass.Low
            classMb in 1..191 -> DeviceComputeClass.Low
            totalMb >= 6144L || classMb >= 384 -> DeviceComputeClass.High
            else -> DeviceComputeClass.Mid
        }
    }.getOrDefault(DeviceComputeClass.Mid)
}
