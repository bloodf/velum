package dev.velum.app.i18n

/**
 * Same output as `"%.<decimals>f".format(value)` on the JVM: device-locale separator, no
 * grouping, HALF_UP on the shortest decimal digits (2.675 -> "2.68" at 2 decimals).
 */
expect fun formatFixed(value: Double, decimals: Int): String

/** `template.format(*args)` for the specifiers the app uses: `%s`, `%d`, `%0Nd`, `%%`. */
expect fun formatTemplate(template: String, vararg args: Any): String

/**
 * Locale-free [formatFixed] with `.` as separator, backing non-JVM actuals. Mirrors
 * java.util.Formatter: rounds the decimal digits of [Double.toString], not the binary value.
 */
internal fun fixedHalfUpAscii(value: Double, decimals: Int): String {
    require(decimals >= 0) { "decimals must not be negative: $decimals" }
    if (value.isNaN() || value.isInfinite()) return value.toString()
    val raw = value.toString()
    val negative = raw.startsWith('-')
    val unsigned = raw.removePrefix("-")
    val mantissa = unsigned.substringBefore('E')
    val intDigits = mantissa.substringBefore('.')
    var digits = intDigits + mantissa.substringAfter('.', "")
    var point = intDigits.length + unsigned.substringAfter('E', "0").toInt()
    if (point < 0) {
        digits = "0".repeat(-point) + digits
        point = 0
    }
    val keep = point + decimals
    digits = digits.padEnd(keep + 1, '0')
    var kept = digits.substring(0, keep)
    if (digits[keep] >= '5') {
        val bumped = incrementDigits(kept)
        point += bumped.length - kept.length
        kept = bumped
    }
    val whole = kept.substring(0, point).trimStart('0').ifEmpty { "0" }
    val sign = if (negative) "-" else ""
    return if (decimals == 0) sign + whole else "$sign$whole.${kept.substring(point)}"
}

private fun incrementDigits(digits: String): String {
    val chars = digits.toCharArray()
    for (i in chars.indices.reversed()) {
        if (chars[i] != '9') {
            chars[i] = chars[i] + 1
            return chars.concatToString()
        }
        chars[i] = '0'
    }
    return "1" + chars.concatToString()
}

private val TEMPLATE_SPECIFIER = Regex("%(?:(0?)(\\d*)([ds])|%)")

/** Locale-free [formatTemplate], backing non-JVM actuals. */
internal fun fillTemplateAscii(template: String, args: Array<out Any>): String {
    var next = 0
    return TEMPLATE_SPECIFIER.replace(template) { match ->
        if (match.value == "%%") return@replace "%"
        val arg = args[next++].toString()
        if (match.groupValues[3] == "s") return@replace arg
        val width = match.groupValues[2].toIntOrNull() ?: 0
        val sign = if (arg.startsWith('-')) "-" else ""
        val magnitude = arg.removePrefix("-")
        if (match.groupValues[1] == "0") sign + magnitude.padStart(width - sign.length, '0')
        else (sign + magnitude).padStart(width)
    }
}
