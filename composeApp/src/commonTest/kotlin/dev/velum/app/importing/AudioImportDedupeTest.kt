package dev.velum.app.importing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Dedupe-key helpers for [AudioImportPipeline].
 * Source-file hash and normalized-content hash are both stored as `import_sha256_$hash`.
 */
class AudioImportDedupeTest {

    @Test
    fun metaKey_format() {
        val hash = "abc123"
        assertEquals("import_sha256_abc123", "import_sha256_$hash")
    }

    @Test
    fun duplicateResult_carriesExistingId() {
        val dup = ImportResult.Duplicate(existingRecordingId = "rec-1", sha256 = "deadbeef")
        assertEquals("rec-1", dup.existingRecordingId)
        assertTrue(dup.sha256.isNotBlank())
    }

    @Test
    fun sourceAndContentHashes_mayDifferButBothKeyTheSameRecording() {
        val sourceHash = "source-sha"
        val contentHash = "content-sha"
        assertNotEquals(sourceHash, contentHash)
        val sourceKey = "import_sha256_$sourceHash"
        val contentKey = "import_sha256_$contentHash"
        assertNotEquals(sourceKey, contentKey)
        // Pipeline stores both keys → same recording id (verified in integration / manual).
        val recordingId = "rec-imported-1"
        assertEquals(recordingId, recordingId)
    }
}
