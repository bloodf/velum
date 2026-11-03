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
}
