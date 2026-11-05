package dev.velum.app.i18n

/**
 * Where new user-facing strings go. [Strings] and [UxStrings] are at the JVM limit for a constructor's parameters
 * (255 slots, including the synthetic `copy$default`, which allows 245 fields): one more field in UxStrings fails at class load
 * with "Too many arguments". Make this a `data class` and add each new string as a constructor property, then give it a value in the `added = MoreStrings(...)`
 * of both `PtBr` and `En` (Strings.kt). `StringsSizeTest` fails before any class gets this close, and says which one.
 *
 * Do not move existing fields: many open changes touch them.
 */
data class MoreStrings(
    val webhookFailedTimeout: String,
    val webhookFailedUnresolvedHost: String,
    val webhookFailedTls: String,
    val webhookFailedConnection: String,
    val speakerCacheNotDropped: String,
    val processingFailedApiKey: String,
    val processingFailedAudioMissing: String,
    val processingFailedAudioEmpty: String,
    val processingFailedWavNoData: String,
    val deleteForeverFailed: String,
    val speakersChangedWhileOpen: String,
    val voiceProfilesExportCopy: String,
    val voiceProfilesExportCopied: String,
)
