package dev.velum.app.platform

import java.io.File
import java.util.UUID

/** Existing share/recovery tests exercise their callers with a local transport, never a host PID kill. */
internal fun installLocalProviderCopyTestTransport() {
    providerCopyTestTransport = { context, uri, share ->
        val name = IncomingShare.cleanName(displayNameOf(context, uri))
        val extension = name?.takeIf { ImportAudioFormats.isSupportedFileName(it) }?.substringAfterLast('.')?.lowercase() ?: "audio"
        val dir = File(context.cacheDir, "imports").apply { mkdirs() }
        val file = File(dir, "${if (share) "share_" else ""}${UUID.randomUUID()}.$extension")
        if (copyToCache({ context.contentResolver.openInputStream(uri) }, file)) ProviderCopyResult(file, name) else null
    }
}
