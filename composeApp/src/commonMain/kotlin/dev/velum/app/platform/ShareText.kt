package dev.velum.app.platform

/** Share plain text (Markdown export). Android uses ACTION_SEND; desktop copies or writes a file. */
expect fun shareText(title: String, text: String)
