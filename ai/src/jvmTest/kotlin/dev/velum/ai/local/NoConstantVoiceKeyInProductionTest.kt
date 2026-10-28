package dev.velum.ai.local

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * CTO rule (#110): the constant `(it * 17 + 41)` voice-profile key must exist only in
 * test sources. Production `src/` + `*Main` trees must not ship the formula or a helper
 * that returns it.
 */
class NoConstantVoiceKeyInProductionTest {

    @Test
    fun production_sources_have_no_constant_voice_key_formula() {
        val roots = resolveMainRoots()
        assertTrue(roots.isNotEmpty(), "expected at least one production Main source tree")
        val offenders = mutableListOf<String>()
        val banned = listOf(
            "legacyConstantKeyBytes",
            "it * 17 + 41",
            "(it * 17 + 41)",
        )
        for (root in roots) {
            root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .forEach { file ->
                    val text = file.readText()
                    for (needle in banned) {
                        if (text.contains(needle)) {
                            offenders += "${rel(file)} contains \"$needle\""
                        }
                    }
                }
        }
        assertTrue(
            offenders.isEmpty(),
            "constant voice-profile key must not ship in production:\n" +
                offenders.joinToString("\n"),
        )
    }

    private fun resolveMainRoots(): List<File> {
        val repo = resolveRepoRoot()
        return listOf("ai", "core", "ble", "composeApp", "recorder", "plaud-protocol")
            .map { File(repo, "$it/src") }
            .filter { it.isDirectory }
            .flatMap { src ->
                src.listFiles()
                    ?.filter { it.isDirectory && it.name.endsWith("Main") }
                    ?.map { File(it, "kotlin") }
                    ?.filter { it.isDirectory }
                    .orEmpty()
            }
    }

    private fun resolveRepoRoot(): File {
        val candidates = listOf(
            File("."),
            File(".."),
            File("../.."),
            File("/workspace"),
        )
        return candidates.firstOrNull { File(it, "ai/src/commonMain").isDirectory }
            ?: error("cannot locate repo root (cwd=${File(".").absolutePath})")
    }

    private fun rel(file: File): String {
        val root = resolveRepoRoot().canonicalFile
        return file.canonicalFile.relativeToOrSelf(root).path
    }
}
