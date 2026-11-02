package dev.velum.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Ratchet against JVM-only APIs in shared source sets (`commonMain`, `mobileMain`) of every module.
 *
 * This is a heuristic token scan over source with comments and string literals blanked (code inside
 * `${...}` templates is still scanned). It catches the usual iOS-breaking slips early on the JVM CI
 * lane; it is NOT a substitute for compiling the iOS targets (`compileKotlinIosSimulatorArm64`).
 *
 * Known offences live in [BASELINE] as one `path:rule` line per occurrence. The test fails on any
 * new offence and on any stale baseline line, so the baseline only ever shrinks.
 */
class CommonMainPortabilityTest {
    private class Rule(val id: String, val pattern: Regex, val fix: String)

    private data class Offence(val path: String, val line: Int, val rule: Rule) {
        val key get() = "$path:${rule.id}"
    }

    private val root = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private val rules = listOf(
        Rule("synchronized", Regex("""\bsynchronized\s*\(|@Synchronized\b"""), "kotlinx.coroutines.sync.Mutex, or MutableStateFlow.update for atomic state"),
        Rule("string-format", Regex("""\.format\s*\("""), "string templates with padStart/roundToInt, or an expect/actual formatter"),
        Rule("java-import", Regex("""^\s*import\s+javax?\."""), "move the JVM code to a platform source set behind expect/actual"),
        Rule("System", Regex("""\bSystem\."""), "kotlin.time (Clock.System, TimeSource) or expect/actual"),
        Rule("Thread", Regex("""\bThread\."""), "coroutines (delay, Dispatchers)"),
        Rule("Class.forName", Regex("""\bClass\.forName\b"""), "KClass references or expect/actual"),
        Rule("class.java", Regex("""::class\.java\b"""), "KClass (::class) or expect/actual"),
        Rule("sorted-collection", Regex("""\b(toSortedMap|sortedMapOf|TreeMap|TreeSet|toSortedSet)\b"""), "sortedBy/sortedWith on a list, then associate/groupBy"),
        Rule("ConcurrentHashMap", Regex("""\bConcurrentHashMap\b"""), "MutableStateFlow<Map> with update, or a Mutex"),
        Rule("java-atomic", Regex("""\bjava\.util\.concurrent\.atomic\b"""), "MutableStateFlow.update or kotlin.concurrent.atomics"),
        Rule("Character", Regex("""\bCharacter\."""), "Char members (isDigit, isLetter, code)"),
        Rule("Math", Regex("""\bMath\."""), "kotlin.math"),
        Rule("Locale", Regex("""\bLocale\b"""), "language-tag strings, or expect/actual"),
        Rule("case-conversion", Regex("""\.(toUpperCase|toLowerCase|capitalize)\s*\("""), "uppercase()/lowercase()/replaceFirstChar"),
        Rule("ByteBuffer", Regex("""\bByteBuffer\b"""), "ByteArray with explicit little/big-endian helpers"),
        Rule("File", Regex("""\bFile\("""), "expect/actual storage (paths as String)"),
    )

    /** `usage` needs an import of `import` (or its package star import) to compile on non-JVM targets. */
    private val requiredImports = listOf(
        Triple(Rule("Dispatchers.IO-import", Regex("""\bDispatchers\.IO\b"""), "add `import kotlinx.coroutines.IO`"), "kotlinx.coroutines", "IO"),
        Triple(Rule("Volatile-import", Regex("""@Volatile\b"""), "add `import kotlin.concurrent.Volatile`"), "kotlin.concurrent", "Volatile"),
    )

    @Test
    fun shared_source_sets_match_portability_baseline() {
        val sources = root.listFiles().orEmpty()
            .filter { it.isDirectory && !it.name.startsWith(".") && it.name != "build" }
            .flatMap { module -> listOf("commonMain", "mobileMain").map { File(module, "src/$it") } }
            .filter { it.isDirectory }
            .flatMap { set -> set.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
        assertTrue(sources.any { it.path.endsWith("core/src/commonMain/kotlin/dev/velum/core/templates/PlaudTemplateCatalog.kt") }, "scan root wrong: $root")

        val offences = sources.flatMap { scan(it.relativeTo(root).invariantSeparatorsPath, it.readText()) }
        val baselineFile = File(root, BASELINE)
        val baseline = baselineFile.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }

        val remaining = baseline.groupingBy { it }.eachCount().toMutableMap()
        val fresh = offences.filter { o ->
            val left = remaining[o.key] ?: 0
            if (left > 0) remaining[o.key] = left - 1
            left == 0
        }
        val stale = remaining.filterValues { it > 0 }.flatMap { (key, n) -> List(n) { key } }

        val report = buildString {
            if (fresh.isNotEmpty()) {
                appendLine("New JVM-only API use in shared source sets (fix it, do not baseline it):")
                fresh.forEach { appendLine("  ${it.path}:${it.line} [${it.rule.id}] -> ${it.rule.fix}") }
            }
            if (stale.isNotEmpty()) {
                appendLine("Stale lines in $BASELINE (offence fixed; delete these lines):")
                stale.forEach { appendLine("  $it") }
            }
        }
        assertTrue(report.isEmpty(), report)
    }

    @Test
    fun scanner_ignores_comments_and_strings_but_sees_template_code() {
        val src = """
            |import kotlinx.coroutines.Dispatchers
            |// synchronized(lock) in a comment
            |/* nested /* TreeMap */ still comment */
            |val a = "Math.max and synchronized( inside a string"
            |val b = ""${'"'}raw Thread.sleep""${'"'}
            |val c = '"'
            |val d = "${'$'}{"%.1f".format(x)} KB"
            |val e = Dispatchers.IO
        """.trimMargin()
        val hits = scan("x.kt", src).map { "${it.line}:${it.rule.id}" }
        assertEquals(listOf("7:string-format", "8:Dispatchers.IO-import"), hits)
    }

    private fun scan(path: String, text: String): List<Offence> {
        val code = stripCommentsAndStrings(text)
        val lines = code.lines()
        val tokenHits = lines.flatMapIndexed { index, line ->
            rules.flatMap { rule -> rule.pattern.findAll(line).map { Offence(path, index + 1, rule) }.toList() }
        }
        val importHits = requiredImports.mapNotNull { (rule, pkg, name) ->
            val first = lines.indexOfFirst { rule.pattern.containsMatchIn(it) }
            val imported = Regex("""^\s*import\s+${Regex.escape(pkg)}\.(${Regex.escape(name)}|\*)\s*$""", RegexOption.MULTILINE)
                .containsMatchIn(code)
            if (first >= 0 && !imported) Offence(path, first + 1, rule) else null
        }
        return tokenHits + importHits
    }

    /** Blank comments, char and string literal contents (keeping newlines and `${...}` code). */
    private fun stripCommentsAndStrings(src: String): String = Lexer(src).run()

    private class Lexer(private val src: String) {
        private val out = StringBuilder(src.length)

        fun run(): String {
            code(0, nested = false)
            return out.toString()
        }

        private fun blank(from: Int, to: Int) {
            for (k in from until minOf(to, src.length)) out.append(if (src[k] == '\n') '\n' else ' ')
        }

        /** Copies code from [start]; when [nested], stops at the `}` closing a `${...}` template. */
        private fun code(start: Int, nested: Boolean): Int {
            var i = start
            var depth = 0
            while (i < src.length) {
                val c = src[i]
                when {
                    src.startsWith("//", i) -> {
                        val end = src.indexOf('\n', i).let { if (it < 0) src.length else it }
                        blank(i, end)
                        i = end
                    }
                    src.startsWith("/*", i) -> i = blockComment(i)
                    c == '"' -> i = string(i)
                    c == '\'' -> i = charLiteral(i)
                    c == '}' && nested && depth == 0 -> return i
                    else -> {
                        if (c == '{') depth++
                        if (c == '}') depth--
                        out.append(c)
                        i++
                    }
                }
            }
            return i
        }

        private fun blockComment(start: Int): Int {
            var j = start
            var level = 0
            while (j < src.length) {
                when {
                    src.startsWith("/*", j) -> { level++; j += 2 }
                    src.startsWith("*/", j) -> { level--; j += 2; if (level == 0) break }
                    else -> j++
                }
            }
            blank(start, j)
            return j
        }

        private fun charLiteral(start: Int): Int {
            var j = start + 1
            if (j < src.length && src[j] == '\\') j++
            j++
            while (j < src.length && src[j] != '\'' && src[j] != '\n') j++
            val end = if (j < src.length && src[j] == '\'') j + 1 else start + 1
            blank(start, end)
            return end
        }

        private fun string(start: Int): Int {
            val raw = src.startsWith("\"\"\"", start)
            val quote = if (raw) "\"\"\"" else "\""
            out.append(quote)
            var i = start + quote.length
            while (i < src.length) {
                when {
                    !raw && src[i] == '\\' -> { blank(i, i + 2); i += 2 }
                    src.startsWith("\${", i) -> {
                        out.append("\${")
                        i = code(i + 2, nested = true)
                        if (i < src.length) { out.append('}'); i++ }
                    }
                    src.startsWith(quote, i) -> {
                        // A raw string may close with extra quotes: """a"""" ends after the last one.
                        var end = i + quote.length
                        while (raw && end < src.length && src[end] == '"') end++
                        blank(i, end - quote.length)
                        out.append(quote)
                        return end
                    }
                    !raw && src[i] == '\n' -> return i
                    else -> { blank(i, i + 1); i++ }
                }
            }
            return i
        }
    }

    private companion object {
        const val BASELINE = "core/src/jvmTest/resources/commonmain-portability-baseline.txt"
    }
}
