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

    // Robolectric cannot tell the real database rewrite from nothing, so pin the fallback in the source (#423).
    @Test fun the_last_wipe_step_falls_back_to_the_real_database_rewrite() {
        val source = File("src/commonMain/kotlin/dev/velum/app/di/AppContainer.kt").readText()
        assertTrue("step(\"database\") { (compactDatabaseForTest ?: repository::compactAfterWipe)() }" in source, "the app must run compactAfterWipe")
        assertTrue(source.indexOf("step(\"database\")") > source.indexOf("step(\"recordings\")"), "the rewrite runs after the rows are deleted")
    }
}
