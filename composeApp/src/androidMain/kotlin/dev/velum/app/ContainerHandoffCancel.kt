package dev.velum.app

import android.content.Context
import android.util.Log
import dev.velum.ai.pipeline.inAppLanguage

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

/**
 * The fatal screen's text in the app's language (#348). Call it off the main thread: settings may have failed to load,
 * and the saved language that stands in for them cannot be read on main. Reading it must not stop the fatal screen from
 * ending the splash, so any failure falls back to the device's language.
 */
internal fun Context.fatalStartText(): String =
    runCatching { inAppLanguage().getString(R.string.fatal_start_failed) }
        .getOrElse { t ->
            Log.w("VelumMain", "Fatal text language lookup failed (${t::class.simpleName})")
            getString(R.string.fatal_start_failed)
        }
