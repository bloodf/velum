package dev.velum.app.ui.files

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** #365: import and share rows are not drawn with the headphones icon. */
class LibrarySourceIconTest {
    @Test fun imports_and_shares_get_the_import_icon() {
        assertEquals(LibraryListLogic.SourceIcon.Import, LibraryListLogic.sourceIcon("import"))
        assertEquals(LibraryListLogic.SourceIcon.Import, LibraryListLogic.sourceIcon("share"))
        assertEquals(LibraryListLogic.SourceIcon.Import, LibraryListLogic.sourceIcon("watched"))
    }

    @Test fun note_pro_and_phone_keep_their_icons() {
        assertEquals(LibraryListLogic.SourceIcon.NotePro, LibraryListLogic.sourceIcon("note_pro"))
        assertEquals(LibraryListLogic.SourceIcon.Phone, LibraryListLogic.sourceIcon("phone"))
        assertNotEquals(LibraryListLogic.SourceIcon.Import, LibraryListLogic.sourceIcon("phone"))
    }

    @Test fun the_accessibility_name_treats_watched_like_import() {
        assertEquals(
            dev.velum.app.i18n.En.ux.importedAudioA11y,
            LibraryListLogic.sourceA11y("watched", dev.velum.app.i18n.En),
        )
        assertEquals(
            LibraryListLogic.sourceA11y("import", dev.velum.app.i18n.PtBr),
            LibraryListLogic.sourceA11y("watched", dev.velum.app.i18n.PtBr),
        )
    }
}
