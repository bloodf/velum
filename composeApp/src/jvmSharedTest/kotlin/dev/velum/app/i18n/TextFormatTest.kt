package dev.velum.app.i18n

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The JVM actuals delegate to String.format, so Android output is unchanged by construction.
 * The common helpers back the iOS actuals; they must match java.util.Formatter digit for digit.
 */
class TextFormatTest {
    private fun <T> withLocale(locale: Locale, block: () -> T): T {
        val saved = Locale.getDefault()
        Locale.setDefault(locale)
        return try { block() } finally { Locale.setDefault(saved) }
    }

    @Test
    fun formatFixed_on_jvm_rounds_half_up_on_decimal_digits() {
        assertEquals("2.68", withLocale(Locale.ROOT) { formatFixed(2.675, 2) })
    }

    @Test
    fun formatFixed_uses_device_locale_separator_without_grouping() {
        assertEquals("1,5", withLocale(Locale.forLanguageTag("pt-BR")) { formatFixed(1.5, 1) })
        assertEquals("1.5", withLocale(Locale.ENGLISH) { formatFixed(1.5, 1) })
        assertEquals("1234567,50", withLocale(Locale.forLanguageTag("pt-BR")) { formatFixed(1234567.5, 2) })
    }

    @Test
    fun formatTemplate_on_jvm_matches_string_format_for_every_shipped_template() = withLocale(Locale.ROOT) {
        for (strings in listOf(PtBr, En)) {
            assertEquals(
                String.format(strings.ux.syncDoneFormat, "3", "0", "12"),
                formatTemplate(strings.ux.syncDoneFormat, "3", "0", "12"),
            )
            for (t in listOf(strings.ux.webhookPostedFormat, strings.ux.webhookFailedFormat)) {
                assertEquals(String.format(t, "HTTP 200"), formatTemplate(t, "HTTP 200"))
            }
        }
    }

    @Test
    fun common_fixed_helper_matches_java_formatter() {
        val values = listOf(
            0.0, -0.0, 0.05, 0.15, 0.25, 0.35, 0.45, 0.5, 0.95, 0.125, 0.375, 0.995, 1.005, 1.5, 2.5, 2.675,
            9.95, 9.995, 99.5, 99.95, 100.0, 1023.95, 1023.999, 1024.0, 1234567.5, 1.0E7, 1.5E10, 1.0E-3,
            1.0E-5, 5.0E-5, 9.99E-4, -0.04, -0.05, -1.5, -2.675, -99.5, 0.1 + 0.2, 1.0 / 3.0, 2.0 / 3.0,
            Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
        ) + (0..2000).map { it / 16.0 } + (0..2000).map { it * 0.1 } + (0..1000).map { -it * 0.015 }
        for (decimals in 0..3) {
            for (v in values) {
                assertEquals(
                    String.format(Locale.ROOT, "%.${decimals}f", v),
                    fixedHalfUpAscii(v, decimals),
                    "value=$v decimals=$decimals",
                )
            }
        }
    }

    @Test
    fun common_template_helper_matches_java_formatter() {
        val cases = listOf<Pair<String, Array<Any>>>(
            "%d:%02d:%02d" to arrayOf(1L, 2L, 3L),
            "%d:%02d:%02d" to arrayOf(100L, 59L, 59L),
            "%02d:%02d" to arrayOf(0L, 0L),
            "%02d:%02d" to arrayOf(123L, 7L),
            "%02d" to arrayOf(-5L),
            "%03d" to arrayOf(-5L),
            "%d" to arrayOf(-5),
            "Pronto · %d novos · %d duplicados · %d falhas" to arrayOf(1, 2, 3),
            "Webhook falhou: %s" to arrayOf("HTTP 500 %d literal"),
            "100%% %s" to arrayOf("ok"),
        )
        for ((template, args) in cases) {
            assertEquals(String.format(Locale.ROOT, template, *args), fillTemplateAscii(template, args), template)
        }
    }
}
