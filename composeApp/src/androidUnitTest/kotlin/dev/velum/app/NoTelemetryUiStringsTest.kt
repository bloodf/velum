package dev.velum.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * UI must never advertise telemetry / analytics / crash-reporting opt-ins.
 * Scans shipped string tables (en + pt-BR) for banned phrases.
 */
class NoTelemetryUiStringsTest {
    private val bannedPhrases = listOf(
        "Help improve Velum",
        "Ajudar a melhorar o Velum",
        "anonymous usage data",
        "dados de uso anônimos",
        "Share anonymous",
        "Compartilhar dados de uso",
        "usage analytics",
        "More apps coming",
        "Mais apps em breve",
        "coming soon",
        "More integrations arriving",
        "Mais integrações chegando",
        "Photo to template",
        "Foto para Template",
        "helpImprove",
        "appLock",
        "moreAppsComing",
        "photoToTemplate",
        "setForLater",
        "sendSuggestion",
        "currentWorkspace",
    )

    @Test
    fun stringsKt_hasNoTelemetryOrComingSoonChrome() {
        val file = resolveStringsKt()
        assertTrue(file.isFile, "Strings.kt not found")
        val text = file.readText()
        for (phrase in bannedPhrases) {
            assertFalse(
                text.contains(phrase, ignoreCase = true),
                "Strings.kt must not contain unimplemented/telemetry chrome: \"$phrase\"",
            )
        }
    }

    private fun resolveStringsKt(): File {
        val candidates = listOf(
            File("src/commonMain/kotlin/dev/velum/app/i18n/Strings.kt"),
            File("composeApp/src/commonMain/kotlin/dev/velum/app/i18n/Strings.kt"),
            File("../composeApp/src/commonMain/kotlin/dev/velum/app/i18n/Strings.kt"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("Strings.kt not found from cwd=${File(".").absolutePath}")
    }
}
