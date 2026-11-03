package dev.velum.app.ui.files

/** Library row duration: a clip under a minute shows seconds, so a 36 s import is not "0 min". */
internal fun libraryDurationLabel(durationSeconds: Long, minutesFormat: String, secondsFormat: String): String =
    if (durationSeconds < 60) {
        secondsFormat.replace("%d", durationSeconds.coerceAtLeast(0).toString())
    } else {
        minutesFormat.replace("%d", (durationSeconds / 60).toString())
    }
