package dev.velum.app.i18n

import platform.Foundation.NSLocale
import platform.Foundation.NSLocaleDecimalSeparator
import platform.Foundation.currentLocale

actual fun formatFixed(value: Double, decimals: Int): String {
    val separator = NSLocale.currentLocale.objectForKey(NSLocaleDecimalSeparator) as? String ?: "."
    return fixedHalfUpAscii(value, decimals).replace(".", separator)
}

actual fun formatTemplate(template: String, vararg args: Any): String = fillTemplateAscii(template, args)
