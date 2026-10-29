package dev.velum.app

/**
 * Disposition after [CancellationException] while awaiting the process-scoped
 * container in [MainActivity] (#106 / 28fcef0).
 *
 * If the activity is already destroyed, ignore — never flip fatal/ready (splash
 * dies with the activity). If still alive, the cancel was startup itself and
 * must end the splash (fatal UI); otherwise both flags stay false and splash
 * hangs forever.
 */
internal enum class HandoffCancelDisposition {
    Ignore,
    ShowFatal,
}

internal fun dispositionForHandoffCancellation(
    activityDestroyed: Boolean,
): HandoffCancelDisposition =
    if (activityDestroyed) {
        HandoffCancelDisposition.Ignore
    } else {
        HandoffCancelDisposition.ShowFatal
    }
