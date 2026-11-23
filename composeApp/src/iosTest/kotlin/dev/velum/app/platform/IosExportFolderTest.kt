@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.velum.app.platform

import kotlin.test.*
import platform.Foundation.*

class IosExportFolderTest {
    @Test
    fun filenameMustBeOneLeaf() {
        listOf("", " ", ".", "..", "../note.md", "/note.md", "a/b.md", "a\\b.md", "a\u0000.md", "a\n.md")
            .forEach { assertFalse(isExportFilenameLeaf(it), it) }
        listOf("note.md", "My note.md", "ação.md", "..note.md").forEach { assertTrue(isExportFilenameLeaf(it), it) }
    }

    @Test
    fun clearRemovesPersistedSelectionAndNoFolderFails() {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = "velum_export_folder_bookmark"
        val previous = defaults.objectForKey(key)
        try {
            val folder = NSURL.fileURLWithPath(NSTemporaryDirectory(), isDirectory = true)
            val bookmark = assertNotNull(folder.bookmarkDataWithOptions(0u, null, null, null))
            defaults.setObject(bookmark, forKey = key)
            assertNotNull(NSUserDefaults.standardUserDefaults.dataForKey(key))
            assertEquals(folder.path, ExportFolderStore.resolve()?.path)
            ExportFolderStore.clear()
            assertNull(defaults.objectForKey(key))
            assertNull(ExportFolderStore.currentUri())
            assertIs<ExportFolderResult.Failed>(writeMarkdownToExportFolder("note.md", "hello"))
        } finally {
            if (previous != null) defaults.setObject(previous, forKey = key) else ExportFolderStore.clear()
        }
    }

    @Test
    fun writesUtf8AndReportsMissingDirectory() {
        val path = NSTemporaryDirectory() + "velum-export-" + NSUUID().UUIDString
        val files = NSFileManager.defaultManager
        assertTrue(files.createDirectoryAtPath(path, true, null, null))
        try {
            val folder = NSURL.fileURLWithPath(path, isDirectory = true)
            assertIs<ExportFolderResult.Ok>(writeIosExportMarkdown(folder, "note.md", "# Olá\n"))
            assertEquals("# Olá\n", NSString.stringWithContentsOfFile("$path/note.md", NSUTF8StringEncoding, null))
            assertIs<ExportFolderResult.Failed>(writeIosExportMarkdown(folder, "../escape.md", "bad"))
            val missing = NSURL.fileURLWithPath("$path/missing", isDirectory = true)
            assertIs<ExportFolderResult.Failed>(writeIosExportMarkdown(missing, "note.md", "hello"))
        } finally {
            files.removeItemAtPath(path, null)
        }
    }
}
