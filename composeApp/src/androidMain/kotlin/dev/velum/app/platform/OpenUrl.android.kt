package dev.velum.app.platform

import android.content.Intent
import android.net.Uri

actual fun openUrl(url: String) {
    val context = ShareTextHolder.context ?: return
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}
