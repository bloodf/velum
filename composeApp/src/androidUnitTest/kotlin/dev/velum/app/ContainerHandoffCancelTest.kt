package dev.velum.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Splash must always end after container-handoff [CancellationException] (#106 / 28fcef0).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ContainerHandoffCancelTest {
    @Test
    fun cancellation_whileActivityAlive_showsFatal_soSplashEnds() {
        assertEquals(
            HandoffCancelDisposition.ShowFatal,
            dispositionForHandoffCancellation(activityDestroyed = false),
        )
    }

    @Test
    fun cancellation_afterActivityDestroyed_isIgnored_noFatalUi() {
        assertEquals(
            HandoffCancelDisposition.Ignore,
            dispositionForHandoffCancellation(activityDestroyed = true),
        )
    }
}
