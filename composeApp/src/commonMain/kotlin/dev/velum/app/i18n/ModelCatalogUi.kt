package dev.velum.app.i18n

import dev.velum.ai.local.DefaultLocalCatalog
import dev.velum.ai.local.DeviceComputeClass
import dev.velum.ai.local.LocalAsrRouter
import dev.velum.ai.local.detectDeviceComputeClass

/**
 * User-facing copy for on-device models (pt-BR + en). Catalog technical
 * fields in `:ai` stay English for logs; UI must never show engine/JNI/doc paths.
 */
data class ModelUiCopy(
    val blurb: String,
    val languages: String,
)

object ModelCatalogUi {
    private val en: Map<String, ModelUiCopy> = mapOf(
        "parakeet-tdt-0.6b-v3-int8" to ModelUiCopy(
            blurb = "High accuracy for 25 European languages, including Portuguese. ~465 MB.",
            languages = "25 European languages (pt, en, es, fr, de, …)",
        ),
        "whisper-large-v3-turbo" to ModelUiCopy(
            blurb = "Best for non-European languages and mixed-language audio. ~538 MB.",
            languages = "About 99 languages (quality varies by language)",
        ),
        "whistle" to ModelUiCopy(
            blurb = "Smallest option for low-memory phones. English and several European languages. ~17 MB.",
            languages = "en, de, fr, es, it, nl, pl",
        ),
        "whisper-tiny" to ModelUiCopy(
            blurb = "Light multilingual option for low-memory phones — includes Portuguese. ~111 MB.",
            languages = "About 99 languages (includes Portuguese)",
        ),
        "sensevoice-small" to ModelUiCopy(
            blurb = "Light option for Chinese, Cantonese, Japanese, and Korean. ~156 MB.",
            languages = "zh, yue, en, ja, ko",
        ),
        "pyannote-segmentation-3.0" to ModelUiCopy(
            blurb = "Detects when speakers change in a recording. ~5 MB.",
            languages = "Works in any language",
        ),
        "3dspeaker-eres2net-base" to ModelUiCopy(
            blurb = "Matches speakers to saved voice profiles. Recommended default. ~38 MB.",
            languages = "Multilingual",
        ),
        "wespeaker-voxceleb" to ModelUiCopy(
            blurb = "Alternate speaker matching trained mainly on English. ~28 MB.",
            languages = "English-focused",
        ),
        "titanet-small" to ModelUiCopy(
            blurb = "Alternate speaker matching. ~39 MB.",
            languages = "English-focused",
        ),
        "streaming-zipformer-en-20m-mobile" to ModelUiCopy(
            blurb = "Faster live captions for English mic recordings. ~103 MB.",
            languages = "English",
        ),
        "streaming-zipformer-es-kroko" to ModelUiCopy(
            blurb = "Faster live captions for Spanish mic recordings. ~119 MB.",
            languages = "Spanish",
        ),
        "streaming-zipformer-multilang-2025-02" to ModelUiCopy(
            blurb = "Faster live captions for several Asian and other languages (not Portuguese). ~247 MB.",
            languages = "ar, en, id, ja, ru, th, vi, zh",
        ),
        "gemma-4-e4b" to ModelUiCopy(
            blurb = "On-device summaries and Ask AI — higher quality. ~3.6 GB.",
            languages = "35+ tuned languages",
        ),
        "gemma-4-e2b" to ModelUiCopy(
            blurb = "On-device summaries and Ask AI — lighter download. ~2.5 GB.",
            languages = "35+ tuned languages",
        ),
        "qwen3-4b-instruct-2507" to ModelUiCopy(
            blurb = "Alternate on-device summaries. ~2.5 GB.",
            languages = "Many languages",
        ),
    )

    private val ptBr: Map<String, ModelUiCopy> = mapOf(
        "parakeet-tdt-0.6b-v3-int8" to ModelUiCopy(
            blurb = "Precisão alta, 25 idiomas europeus (inclui português). ~465 MB.",
            languages = "25 idiomas europeus (pt, en, es, fr, de, …)",
        ),
        "whisper-large-v3-turbo" to ModelUiCopy(
            blurb = "Melhor para idiomas fora da Europa e áudio misturado. ~538 MB.",
            languages = "Cerca de 99 idiomas (qualidade varia)",
        ),
        "whistle" to ModelUiCopy(
            blurb = "Menor opção para celulares com pouca memória. Inglês e alguns europeus. ~17 MB.",
            languages = "en, de, fr, es, it, nl, pl",
        ),
        "whisper-tiny" to ModelUiCopy(
            blurb = "Opção leve multilíngue para pouca memória — inclui português. ~111 MB.",
            languages = "Cerca de 99 idiomas (inclui português)",
        ),
        "sensevoice-small" to ModelUiCopy(
            blurb = "Opção leve para chinês, cantonês, japonês e coreano. ~156 MB.",
            languages = "zh, yue, en, ja, ko",
        ),
        "pyannote-segmentation-3.0" to ModelUiCopy(
            blurb = "Detecta quando os oradores mudam na gravação. ~5 MB.",
            languages = "Funciona em qualquer idioma",
        ),
        "3dspeaker-eres2net-base" to ModelUiCopy(
            blurb = "Associa oradores aos perfis de voz salvos. Recomendado. ~38 MB.",
            languages = "Multilíngue",
        ),
        "wespeaker-voxceleb" to ModelUiCopy(
            blurb = "Correspondência alternativa de oradores (foco em inglês). ~28 MB.",
            languages = "Foco em inglês",
        ),
        "titanet-small" to ModelUiCopy(
            blurb = "Correspondência alternativa de oradores. ~39 MB.",
            languages = "Foco em inglês",
        ),
        "streaming-zipformer-en-20m-mobile" to ModelUiCopy(
            blurb = "Legendas ao vivo mais rápidas para inglês. ~103 MB.",
            languages = "Inglês",
        ),
        "streaming-zipformer-es-kroko" to ModelUiCopy(
            blurb = "Legendas ao vivo mais rápidas para espanhol. ~119 MB.",
            languages = "Espanhol",
        ),
        "streaming-zipformer-multilang-2025-02" to ModelUiCopy(
            blurb = "Legendas ao vivo para vários idiomas asiáticos e outros (sem português). ~247 MB.",
            languages = "ar, en, id, ja, ru, th, vi, zh",
        ),
        "gemma-4-e4b" to ModelUiCopy(
            blurb = "Resumos e Perguntar à IA no aparelho — maior qualidade. ~3,6 GB.",
            languages = "35+ idiomas ajustados",
        ),
        "gemma-4-e2b" to ModelUiCopy(
            blurb = "Resumos e Perguntar à IA no aparelho — download mais leve. ~2,5 GB.",
            languages = "35+ idiomas ajustados",
        ),
        "qwen3-4b-instruct-2507" to ModelUiCopy(
            blurb = "Resumos no aparelho (alternativa). ~2,5 GB.",
            languages = "Muitos idiomas",
        ),
    )

    fun copyFor(localeTag: String, modelId: String): ModelUiCopy {
        val table = if (localeTag.startsWith("pt", ignoreCase = true)) ptBr else en
        return table[modelId]
            ?: en[modelId]
            ?: ModelUiCopy(blurb = modelId, languages = "")
    }

    fun coveredIds(): Set<String> = en.keys

    /** ASR + diarization + embedding + light LLM for first-run “recommended” pack. */
    fun recommendedModelIds(
        languageHint: String?,
        deviceClass: DeviceComputeClass = detectDeviceComputeClass(),
    ): List<String> {
        val asr = LocalAsrRouter.chooseModelId(languageHint = languageHint, deviceClass = deviceClass)
        return listOf(
            asr,
            "pyannote-segmentation-3.0",
            "3dspeaker-eres2net-base",
            "gemma-4-e2b",
        ).distinct()
    }

    fun recommendedAsrDisplayName(languageHint: String?, deviceClass: DeviceComputeClass): String {
        val id = LocalAsrRouter.chooseModelId(languageHint = languageHint, deviceClass = deviceClass)
        return DefaultLocalCatalog.firstOrNull { it.id == id }?.displayName ?: id
    }
}
