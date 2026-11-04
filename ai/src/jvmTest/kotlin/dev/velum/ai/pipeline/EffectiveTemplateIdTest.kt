package dev.velum.ai.pipeline

import dev.velum.core.templates.PlaudTemplateAliases
import kotlin.test.Test
import kotlin.test.assertEquals

class EffectiveTemplateIdTest {
    @Test fun anExactRowWinsOverAliasResolution() =
        assertEquals("tpl-meeting", effectiveTemplateId("tpl-meeting", "tpl-meeting"))

    @Test fun blankOrAbsentMeansTheDefaultMeetingTemplate() {
        assertEquals("velum.meeting", effectiveTemplateId(null, null))
        assertEquals("velum.meeting", effectiveTemplateId(null, "  "))
    }

    @Test fun aLegacyAliasResolvesToItsBuiltin() = assertEquals("velum.sales", effectiveTemplateId(null, "tpl-sales"))

    @Test fun anUnknownIdIsLeftAlone() = assertEquals("my-own-template", effectiveTemplateId(null, "my-own-template"))

    @Test fun aRemovedDuplicateResolvesToItsSurvivor() {
        val (removed, survivor) = PlaudTemplateAliases.RemovedDuplicates.entries.first()
        assertEquals(survivor, effectiveTemplateId(null, removed))
    }
}
