package dev.velum.app.i18n

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A data class whose constructor has about 246 parameters fails at class load ("Too many arguments", JVM limit 255
 * slots including the synthetic `copy$default`). This fails earlier and names the class, so the fix is made at review
 * time: new strings go to [MoreStrings], not into a class that is nearly full.
 */
class StringsSizeTest {
    private val limit = 246
    private val warnWithin = 5

    // Already at the limit: it may not grow, and is exempt from the "nearly full" rule.
    private val frozen = setOf(UxStrings::class.java)

    private val classes = listOf(
        Strings::class.java,
        SettingsUiStrings::class.java,
        ImportStrings::class.java,
        FirstRunStrings::class.java,
        UxStrings::class.java,
        MoreStrings::class.java,
    )

    private fun fieldCount(c: Class<*>) = c.constructors.maxOf { it.parameterCount }

    @Test fun no_strings_class_is_over_the_jvm_constructor_limit() {
        for (c in classes) {
            val n = fieldCount(c)
            assertTrue(n <= limit, "${c.simpleName} has $n constructor fields, over the safe limit of $limit; move new strings to MoreStrings")
        }
    }

    @Test fun no_strings_class_other_than_the_frozen_ones_is_nearly_full() {
        for (c in classes - frozen) {
            val n = fieldCount(c)
            assertTrue(
                limit - n >= warnWithin,
                "${c.simpleName} has $n of $limit constructor fields (fewer than $warnWithin left); add new strings to MoreStrings instead",
            )
        }
    }

    @Test fun every_data_class_in_strings_kt_is_checked() {
        val source = java.io.File("src/commonMain/kotlin/dev/velum/app/i18n/Strings.kt")
        assertTrue(source.exists(), "Strings.kt not found from ${java.io.File(".").absolutePath}")
        val declared = Regex("^data class (\\w+)\\(", RegexOption.MULTILINE).findAll(source.readText()).map { it.groupValues[1] }.toSet()
        val listed = classes.map { it.simpleName }.toSet()
        assertTrue(listed.containsAll(declared), "Strings.kt declares ${declared - listed}, which StringsSizeTest does not check; add it to the list")
    }
}
