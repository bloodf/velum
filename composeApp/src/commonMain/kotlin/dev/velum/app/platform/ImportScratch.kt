package dev.velum.app.platform

/**
 * True for a temporary copy the app made of an incoming file (picker, share sheet, watched folder) in its cache.
 * Only such a copy may be deleted after an import; a path the user chose must never be.
 */
expect fun isImportScratchPath(absolutePath: String): Boolean

/**
 * Privacy wipe: removes what an interrupted import can leave in the recordings directory (`import_*` outputs and
 * unfinished `*.tmp` / `*.part` files). Rows' own audio is deleted by row; this covers files no row owns.
 */
expect fun deleteImportResidue(recordingsDir: String)
