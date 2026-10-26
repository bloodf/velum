package dev.velum.app.platform

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun rememberJsonFileImporter(onPicked: (jsonText: String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = context.contentResolver.openInputStream(uri)?.use { input ->
            input.readBytes().decodeToString()
        } ?: return@rememberLauncherForActivityResult
        if (text.isNotBlank()) onPicked(text)
    }
    return remember(launcher) {
        {
            launcher.launch(arrayOf("application/json", "text/plain", "text/*", "*/*"))
        }
    }
}
