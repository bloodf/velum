package dev.velum.app.i18n

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A data class with 246 constructor parameters fails at class load (ClassFormatError "Too many arguments in method
 * signature"): the synthetic `copy$default` takes 1 (this) + N + ceil(N/32) masks + 1 marker slots, 255 at N = 245 and 256 at
 * N = 246. Checked by loading generated classes of 244 to 247 fields: 244 and 245 load, 246 and 247 fail. This fails earlier and names the class, so the fix is made at review
 * time: new strings go to [MoreStrings], not into a class that is nearly full.
 */
class StringsSizeTest {
    private val limit = 245
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

    private val dataClassDeclaration = Regex("^\\s*(?:\\w+\\s+)*data class\\s+(\\w+)\\s*[<(]", RegexOption.MULTILINE)

    private fun declaredDataClasses(source: String) = dataClassDeclaration.findAll(source).map { it.groupValues[1] }.toSet()

    @Test fun the_declaration_pattern_finds_indented_modified_and_generic_data_classes() {
        val source = """
            data class Plain(val a: String)
            internal data class Internal(val a: String)
                private  data class Indented(val a: String)
            public data class Generic<T>(val a: T)
            val notAClass = "data class Fake("
            // data class Commented(
            sealed class NotData(val a: String)
        """.trimIndent()
        assertTrue(declaredDataClasses(source) == setOf("Plain", "Internal", "Indented", "Generic"), declaredDataClasses(source).toString())
    }

    @Test fun every_data_class_in_strings_kt_is_checked() {
        val source = java.io.File("src/commonMain/kotlin/dev/velum/app/i18n/Strings.kt")
        assertTrue(source.exists(), "Strings.kt not found from ${java.io.File(".").absolutePath}")
        val declared = declaredDataClasses(source.readText())
        val listed = classes.map { it.simpleName }.toSet()
        assertTrue(listed.containsAll(declared), "Strings.kt declares ${declared - listed}, which StringsSizeTest does not check; add it to the list")
    }
}
