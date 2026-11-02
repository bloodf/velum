package dev.velum.app.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Source scan: Velum* colour tokens must be read at use, never cached in state or in
 * remember/derivedStateOf/lazy bodies, or they freeze at first composition and ignore a theme flip.
 */
class ThemeTokenCachingScanTest {
    private val token = Regex(
        """\bVelum(Bg|Surface|Ink|InkSecondary|Divider|Black|OnBlack|DisabledFill|OnDisabled|WarningContainer|OnWarningContainer|AiPurple|AiCyan)\b|\bAiGradientBrush\b|\bVelumTokenBridge\b""",
    )
    private val stateOfToken = Regex("""mutableStateOf\(\s*Velum""")
    private val cachingBlock = Regex("""\b(remember|rememberSaveable|derivedStateOf|lazy)\b\s*(\([^)]*\))?\s*\{""")

    private fun commonMain(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        return File(checkNotNull(dir) { "settings.gradle.kts not found" }, "composeApp/src/commonMain")
    }

    private fun body(text: String, open: Int): String {
        var depth = 1
        var i = open
        while (depth > 0 && i < text.length) {
            when (text[i]) { '{' -> depth++; '}' -> depth-- }
            i++
        }
        return text.substring(open, i)
    }

    @Test
    fun velumTokensAreNeverCachedInState() {
        val offenders = commonMain().walkTopDown()
            .filter { it.isFile && it.extension == "kt" && !it.path.endsWith("ui/theme/Theme.kt") }
            .flatMap { f ->
                val text = f.readText()
                val bad = mutableListOf<String>()
                if (stateOfToken.containsMatchIn(text)) bad += "${f.name}: mutableStateOf(Velum…)"
                for (m in cachingBlock.findAll(text)) {
                    if (token.containsMatchIn(body(text, m.range.last + 1))) {
                        bad += "${f.name}:${text.substring(0, m.range.first).count { it == '\n' } + 1}: ${m.groupValues[1]} body reads a Velum token"
                    }
                }
                bad.asSequence()
            }.toList()
        assertTrue(offenders.isEmpty(), "Velum tokens cached outside composition reads: $offenders")
    }
}
