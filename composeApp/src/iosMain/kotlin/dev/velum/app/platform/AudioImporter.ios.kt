package dev.velum.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UniformTypeIdentifiers.UTTypeAudio
import platform.darwin.NSObject

/** The picker only keeps a weak delegate reference, so hold it until a result or cancel arrives. */
private var activeDelegate: AudioPickerDelegate? = null

private class AudioPickerDelegate(val onPicked: (String) -> Unit) : NSObject(), UIDocumentPickerDelegateProtocol {
    override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
        activeDelegate = null
        val url = didPickDocumentsAtURLs.firstOrNull() as? NSURL ?: return
        copyIntoImports(url)?.let(onPicked)
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        activeDelegate = null
    }
}

/** Opens the Files picker for audio. `asCopy` gives an app-owned copy, so no security-scoped access is needed. */
@Composable
actual fun rememberAudioImporter(onPicked: (path: String) -> Unit): () -> Unit =
    remember(onPicked) {
        {
            val presenter = topViewController()
            if (presenter != null) {
                val delegate = AudioPickerDelegate(onPicked)
                activeDelegate = delegate
                val picker = UIDocumentPickerViewController(forOpeningContentTypes = listOf(UTTypeAudio), asCopy = true)
                picker.delegate = delegate
                presenter.presentViewController(picker, animated = true, completion = null)
            }
        }
    }

@OptIn(ExperimentalForeignApi::class)
private fun copyIntoImports(source: NSURL): String? {
    val caches = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).firstOrNull() as? String
        ?: return null
    val dir = "$caches/imports"
    val files = NSFileManager.defaultManager
    files.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
    val name = safeImportName(source.lastPathComponent)
    val dest = "$dir/${(NSDate().timeIntervalSince1970 * 1000).toLong()}_$name"
    val ok = files.copyItemAtURL(source, NSURL.fileURLWithPath(dest), error = null)
    return if (ok && files.fileExistsAtPath(dest)) dest else null
}

internal fun safeImportName(raw: String?): String {
    val cleaned = raw.orEmpty().replace("..", "")
        .filter { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }.take(120)
    return cleaned.ifBlank { "import.audio" }
}

private fun topViewController(): UIViewController? {
    val window: UIWindow = UIApplication.sharedApplication.windows.filterIsInstance<UIWindow>()
        .firstOrNull { it.isKeyWindow() } ?: return null
    var top = window.rootViewController
    while (top?.presentedViewController != null) top = top.presentedViewController
    return top
}
