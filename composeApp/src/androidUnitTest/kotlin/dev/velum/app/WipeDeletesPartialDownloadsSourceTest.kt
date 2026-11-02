package dev.velum.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class WipeDeletesPartialDownloadsSourceTest {
    @Test fun wipe_action_deletes_partials_before_anything_else() {
        val source = File("src/commonMain/kotlin/dev/velum/app/ui/settings/StoragePrivacyScreen.kt").readText()
        val partials = source.indexOf("wipePartialDownloads()")
        assertTrue(partials >= 0, "wipe must delete partial downloads")
        assertTrue(partials < source.indexOf("wipeAllUserData"), "a running download must stop before the data goes")
    }
}
