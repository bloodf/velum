package dev.velum.app.platform

import kotlin.test.Test
import kotlin.test.assertEquals

class SafeImportNameTest {
    @Test fun keeps_plain_names() = assertEquals("memo-1_final.m4a", safeImportName("memo-1_final.m4a"))

    @Test fun strips_traversal_and_separators() = assertEquals("etcpasswd.wav", safeImportName("../etc/passwd.wav"))

    @Test fun falls_back_when_nothing_is_left() {
        assertEquals("import.audio", safeImportName(null))
        assertEquals("import.audio", safeImportName("///"))
    }
}
