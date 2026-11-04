package dev.velum.app.platform

import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

/** The picker copies into `<Caches>/imports` (AudioImporter.ios.kt): those copies are scratch. */
actual fun isImportScratchPath(absolutePath: String): Boolean {
    val caches = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).firstOrNull() as? String
        ?: return false
    return absolutePath.startsWith("$caches/imports/") && !absolutePath.contains("/../")
}

// iOS keeps no import outputs in the recordings directory.
actual fun deleteImportResidue(recordingsDir: String) = Unit
