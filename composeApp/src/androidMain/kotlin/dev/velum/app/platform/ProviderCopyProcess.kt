package dev.velum.app.platform

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process

internal const val PROVIDER_COPY_PROCESS_SUFFIX = ":provider_copy"

internal enum class ProviderCopyProcessRole { MAIN, COPY, UNKNOWN }

internal fun providerCopyProcessRole(
    packageName: String,
    sdk: Int,
    publicName: () -> String?,
    ownName: () -> String?,
): ProviderCopyProcessRole {
    val name = runCatching { if (sdk >= 28) publicName() else ownName() }.getOrNull()
    return when (name) {
        packageName -> ProviderCopyProcessRole.MAIN
        packageName + PROVIDER_COPY_PROCESS_SUFFIX -> ProviderCopyProcessRole.COPY
        else -> ProviderCopyProcessRole.UNKNOWN
    }
}

internal fun providerCopyProcessRole(context: Context): ProviderCopyProcessRole = providerCopyProcessRole(
    context.packageName, Build.VERSION.SDK_INT,
    publicName = { Application.getProcessName() },
    ownName = {
        context.getSystemService(ActivityManager::class.java).runningAppProcesses
            ?.firstOrNull { it.pid == Process.myPid() && it.uid == Process.myUid() }?.processName
    },
)

internal fun isProviderCopyProcess(context: Context): Boolean = providerCopyProcessRole(context) == ProviderCopyProcessRole.COPY
