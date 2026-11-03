package dev.velum.app.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImportFileNameTest {
    @Test fun keeps_plain_names() = assertEquals("memo-1_final.m4a", sanitizeImportFileName("memo-1_final.m4a"))

    @Test fun drops_directories_and_traversal() {
        assertEquals("passwd.wav", sanitizeImportFileName("../etc/passwd.wav"))
        assertEquals("x.mp3", sanitizeImportFileName("C:\\a\\b\\x.mp3"))
        assertEquals("ab.mp3", sanitizeImportFileName("a..b.mp3"))
    }

    @Test fun falls_back_when_nothing_is_left() {
        assertEquals("import.audio", sanitizeImportFileName(null))
        assertEquals("import.audio", sanitizeImportFileName("///"))
        assertEquals("x.audio", sanitizeImportFileName("  ", "x.audio"))
    }

    @Test fun long_names_keep_their_extension() {
        val name = sanitizeImportFileName("a".repeat(300) + ".m4a")
        assertEquals(120, name.length)
        assertTrue(name.endsWith(".m4a"))
        assertEquals(120, sanitizeImportFileName("b".repeat(300)).length)
    }
}
