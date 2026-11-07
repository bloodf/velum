package dev.velum.ai.pipeline

import dev.velum.core.templates.BuiltinTemplateCatalog

/**
 * The template id a run actually uses, shared by the processor and AutoFlow so they cannot disagree.
 * An exact row for the requested id wins (an edited builtin keeps its id, #99); otherwise a blank id means the default
 * meeting template and legacy / removed-duplicate ids resolve to their survivors.
 */
internal fun effectiveTemplateId(exactRowId: String?, requestedTemplateId: String?): String =
    exactRowId ?: BuiltinTemplateCatalog.resolveId(requestedTemplateId)
