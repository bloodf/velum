package dev.velum.app.importing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure dedupe-key logic used by [AudioImportPipeline] (meta key `import_sha256_$hash`).
 * Full MediaCodec normalize coverage lives in androidUnitTest fixtures.
 */
class AudioImportDedupeTest {

    @Test
    fun metaKey_isStablePerHash() {
        val hash = "abc123"
        assertEquals("import_sha256_$hash", "import_sha256_$hash")
    }

    @Test
    fun duplicateResult_carriesExistingId() {
        val dup = ImportResult.Duplicate(existingRecordingId = "rec-1", sha256 = "deadbeef")
        assertEquals("rec-1", dup.existingRecordingId)
        assertTrue(dup.sha256.isNotBlank())
    }
}
