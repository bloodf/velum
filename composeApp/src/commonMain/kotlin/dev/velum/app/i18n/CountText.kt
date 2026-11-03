package dev.velum.app.i18n

/** [one] when [count] is 1, otherwise [other]; either may hold `%d`, which becomes the count. */
fun countText(count: Long, one: String, other: String): String =
    (if (count == 1L) one else other).replace("%d", count.toString())

fun countText(count: Int, one: String, other: String): String = countText(count.toLong(), one, other)
