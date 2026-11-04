package dev.velum.ai.pipeline

import dev.velum.core.data.VelumRepository

/**
 * The template id to hand to an enqueue: resolved the way the processor will use it, so the scheduler can tell a
 * job started for `tpl-meeting` from one for `velum.meeting`. A blank template stays null (null-only enqueues keep).
 */
suspend fun resolveTemplateForEnqueue(repository: VelumRepository, templateId: String?): String? =
    templateId?.takeIf { it.isNotBlank() }?.let { effectiveTemplateId(repository.getTemplate(it)?.id, it) }
