package dev.velum.core.autoflow

import dev.velum.core.db.Autoflow
import dev.velum.core.settings.ApiModelOverride

/**
 * Resolve the first enabled AutoFlow that matches a recording candidate.
 * Used at download/import enqueue so WorkManager gets template/model before KEEP can drop them.
 */
object AutoFlowResolve {
    fun firstMatch(
        flows: List<Autoflow>,
        candidate: AutoFlowMatcher.Candidate,
    ): Autoflow? =
        flows.asSequence()
            .filter { it.enabled == 1L }
            .firstOrNull { flow ->
                AutoFlowMatcher.matches(
                    rule = AutoFlowMatcher.Rule(
                        enabled = true,
                        wakePhrase = flow.wake_phrase,
                        durationMinSeconds = flow.duration_min_seconds,
                        durationMaxSeconds = flow.duration_max_seconds,
                        sourceFilter = flow.source_filter,
                        templateId = flow.template_id,
                    ),
                    candidate = candidate,
                )
            }

    fun modelOverride(flow: Autoflow): ApiModelOverride? {
        val modelId = flow.model_id?.takeIf { it.isNotBlank() }
        val profileId = flow.endpoint_profile_id?.takeIf { it.isNotBlank() }
        if (modelId == null && profileId == null) return null
        return ApiModelOverride(profileId = profileId, modelId = modelId)
    }
}
