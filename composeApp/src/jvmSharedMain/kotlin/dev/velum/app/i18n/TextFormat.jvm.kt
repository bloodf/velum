package dev.velum.app.i18n

actual fun formatFixed(value: Double, decimals: Int): String = "%.${decimals}f".format(value)

actual fun formatTemplate(template: String, vararg args: Any): String = template.format(*args)
