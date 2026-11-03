package dev.velum.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class WipeDeletesPartialDownloadsSourceTest {
    @Test fun wipe_action_deletes_partials_before_anything_else() {
        val source = File("src/commonMain/kotlin/dev/velum/app/di/AppContainer.kt").readText()
        val partials = source.indexOf("wipePartialDownloads()")
        assertTrue(partials >= 0, "wipe must delete partial downloads")
        assertTrue(partials < source.indexOf("wipeAllUserData"), "a running download must stop before the data goes")
        val screen = File("src/commonMain/kotlin/dev/velum/app/ui/settings/StoragePrivacyScreen.kt").readText()
        assertTrue("wipeAllDataPartialsLeft" in screen, "a failed delete must be reported")
        assertTrue("wipeAllDataDownloadRunning" in screen, "a download that outlasts the wait must be reported")
    }
}
