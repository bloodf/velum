package dev.velum.app.i18n

import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SavedDownloadUnreadableCopyTest {
    @Test
    fun explainsInBothLanguagesThatTheNextDownloadStartsOver() {
        val en = DeviceUiCopy.savedDownloadUnreadable("en")
        val pt = DeviceUiCopy.savedDownloadUnreadable("pt-BR")
        assertTrue(en.contains("starts over"), en)
        assertTrue(pt.contains("recomeça"), pt)
        assertNotEquals(en, pt)
        assertNotEquals(en, DeviceUiCopy.downloadFailed("en"))
    }
}
