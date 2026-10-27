package dev.velum.core.time

import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** Local-time helpers for recording titles and library timestamps. */
@OptIn(ExperimentalTime::class)
object VelumDateTime {
    fun localDateTime(
        epochSeconds: Long,
        timeZone: TimeZone = TimeZone.currentSystemDefault(),
    ): LocalDateTime =
        Instant.fromEpochSeconds(epochSeconds).toLocalDateTime(timeZone)

    /** `HH:mm` in the device timezone. */
    fun formatClock(
        epochSeconds: Long,
        timeZone: TimeZone = TimeZone.currentSystemDefault(),
    ): String {
        val local = localDateTime(epochSeconds, timeZone)
        return "${two(local.hour)}:${two(local.minute)}"
    }

    /**
     * Friendly default recording title, e.g. `26 out, 10:39` (pt-BR) or `26 Oct, 10:39` (en).
     */
    fun formatFriendlyTitle(
        epochSeconds: Long,
        localeTag: String,
        timeZone: TimeZone = TimeZone.currentSystemDefault(),
    ): String {
        val local = localDateTime(epochSeconds, timeZone)
        val month = monthAbbrev(local.month.ordinal + 1, localeTag)
        return "${local.day} $month, ${two(local.hour)}:${two(local.minute)}"
    }

    fun monthAbbrev(monthNumber: Int, localeTag: String): String {
        val pt = localeTag.startsWith("pt", ignoreCase = true)
        val months = if (pt) {
            listOf("jan", "fev", "mar", "abr", "mai", "jun", "jul", "ago", "set", "out", "nov", "dez")
        } else {
            listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        }
        return months[(monthNumber - 1).coerceIn(0, 11)]
    }

    private fun two(n: Int): String = if (n < 10) "0$n" else "$n"
}
