package dev.velum.app.i18n

import dev.velum.core.time.VelumDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

class AppLanguageTest {
    // The month part of "<day> <month>, hh:mm" for a fixed instant, whatever the test machine's time zone.
    private fun month(appLanguage: String, systemTag: String): String {
        val title = recordingTitle(1_755_000_000L, appLanguage, systemTag)
        return title.substringAfter(' ').substringBefore(',')
    }

    @Test fun system_on_an_english_phone_gives_english_month_names() = assertEquals("Aug", month("system", "en"))

    @Test fun system_on_a_pt_br_phone_gives_portuguese_month_names() = assertEquals("ago", month("system", "pt-BR"))

    @Test fun explicit_language_wins_over_the_device() {
        assertEquals("Aug", month("en", "pt-BR"))
        assertEquals("ago", month("pt-BR", "en"))
    }

    @Test fun resolve_maps_only_the_two_explicit_tags() {
        assertEquals("en", resolveLanguageTag("en", "pt-BR"))
        assertEquals("pt-BR", resolveLanguageTag("pt-BR", "en"))
        assertEquals("fr", resolveLanguageTag("system", "fr"))
        assertEquals(VelumDateTime.monthAbbrev(8, "fr"), "Aug")
    }
}
