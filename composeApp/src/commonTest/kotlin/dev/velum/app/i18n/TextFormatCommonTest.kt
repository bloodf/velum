package dev.velum.app.i18n

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Literal-pinned cases for the common helpers behind the iOS actuals; no JVM APIs, so any target can run them. */
class TextFormatCommonTest {
    @Test
    fun fixed_helper_pins_java_half_up_on_decimal_digits() {
        assertEquals("0.1", fixedHalfUpAscii(0.05, 1))
        assertEquals("0.13", fixedHalfUpAscii(0.125, 2))
        assertEquals("2.68", fixedHalfUpAscii(2.675, 2)) // binary value is below .675; Java still rounds up
        assertEquals("1.0", fixedHalfUpAscii(0.95, 1))
        assertEquals("100", fixedHalfUpAscii(99.5, 0))
        assertEquals("-0.0", fixedHalfUpAscii(-0.04, 1))
    }

    @Test
    fun template_helper_pins_numeric_and_literal_specifiers() {
        assertEquals("1:02:03", fillTemplateAscii("%d:%02d:%02d", arrayOf(1L, 2L, 3L)))
        assertEquals("05:09", fillTemplateAscii("%02d:%02d", arrayOf(5L, 9L)))
        assertEquals("-05", fillTemplateAscii("%03d", arrayOf(-5L)))
        assertEquals("100% ok", fillTemplateAscii("100%% %s", arrayOf("ok")))
    }

    @Test
    fun sync_done_and_webhook_templates_render_exact_text_in_both_locales() {
        assertEquals("Pronto · 7 novos · 8 duplicados · 9 falhas", fillTemplateAscii(PtBr.ux.syncDoneFormat, arrayOf(7, 8, 9)))
        assertEquals("Done · 7 new · 8 dupes · 9 failed", fillTemplateAscii(En.ux.syncDoneFormat, arrayOf(7, 8, 9)))
        assertEquals("Webhook enviado (HTTP 200)", fillTemplateAscii(PtBr.ux.webhookPostedFormat, arrayOf("HTTP 200")))
        assertEquals("Webhook posted (HTTP 200)", fillTemplateAscii(En.ux.webhookPostedFormat, arrayOf("HTTP 200")))
        assertEquals("Webhook falhou: HTTP 500", fillTemplateAscii(PtBr.ux.webhookFailedFormat, arrayOf("HTTP 500")))
        assertEquals("Webhook failed: HTTP 500", fillTemplateAscii(En.ux.webhookFailedFormat, arrayOf("HTTP 500")))
    }

    @Test
    fun sync_done_and_webhook_templates_in_both_locales_fill_cleanly() {
        for ((name, strings) in listOf("PtBr" to PtBr, "En" to En)) {
            check(name, strings.ux.syncDoneFormat, arrayOf(7, 8, 9))
            check(name, strings.ux.webhookPostedFormat, arrayOf("HTTP 200"))
            check(name, strings.ux.webhookFailedFormat, arrayOf("HTTP 500"))
        }
    }

    private fun check(name: String, template: String, args: Array<Any>) {
        val specifiers = Regex("%[ds]").findAll(template).count()
        assertEquals(args.size, specifiers, "$name placeholder count in \"$template\"")
        val filled = fillTemplateAscii(template, args)
        assertFalse('%' in filled, "$name stray % in \"$template\" -> \"$filled\"")
        for (arg in args) assertTrue(arg.toString() in filled, "$name missing $arg in \"$filled\"")
    }
}
