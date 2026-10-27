package dev.velum.core.time

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.time.ExperimentalTime
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

@OptIn(ExperimentalTime::class)
class VelumDateTimeTest {
    @Test
    fun formatClock_usesTimezoneNotUtcModulo() {
        val zone = TimeZone.of("America/Sao_Paulo")
        val local = LocalDateTime(2024, 10, 26, 10, 39, 0)
        val epoch = local.toInstant(zone).epochSeconds

        assertEquals("10:39", VelumDateTime.formatClock(epoch, zone))
        // Same instant is later in UTC — proves we are not using UTC modulo.
        assertNotEquals("10:39", VelumDateTime.formatClock(epoch, TimeZone.UTC))
    }

    @Test
    fun formatFriendlyTitle_ptBrAndEn() {
        val zone = TimeZone.of("America/Sao_Paulo")
        val local = LocalDateTime(2024, 10, 26, 10, 39, 0)
        val epoch = local.toInstant(zone).epochSeconds

        assertEquals("26 out, 10:39", VelumDateTime.formatFriendlyTitle(epoch, "pt-BR", zone))
        assertEquals("26 Oct, 10:39", VelumDateTime.formatFriendlyTitle(epoch, "en", zone))
    }
}
