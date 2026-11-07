package dev.velum.app.platform

import android.content.Intent

actual fun shareText(title: String, text: String) {
    val context = ShareTextHolder.context
        ?: error("ShareTextHolder.context not set — call from Activity after setContent")
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, title)
        putExtra(Intent.EXTRA_TEXT, text)
    }
    val chooser = Intent.createChooser(intent, title)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(chooser)
}

object ShareTextHolder {
    @Volatile
    var context: android.content.Context? = null
}
