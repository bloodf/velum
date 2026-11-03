package dev.velum.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.Foundation.NSError
import platform.Foundation.NSFileSize
import platform.Foundation.NSLog
import platform.Foundation.NSNumber
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
private val importScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

private class AudioPickerDelegate(val onPicked: (String) -> Unit, val onFailed: () -> Unit) : NSObject(), UIDocumentPickerDelegateProtocol {
    override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
        activeDelegate = null
        val url = didPickDocumentsAtURLs.firstOrNull() as? NSURL ?: return
        importScope.launch {
            val path = withContext(Dispatchers.IO) { moveIntoImports(url) }
            if (path != null) onPicked(path) else onFailed()
        }
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        activeDelegate = null
    }
}

/** Opens the Files picker for audio. `asCopy` gives an app-owned copy, so no security-scoped access is needed. */
@Composable
actual fun rememberAudioImporter(onFailed: () -> Unit, onPicked: (path: String) -> Unit): () -> Unit =
    remember(onPicked, onFailed) {
        {
            val presenter = topViewController()
            if (presenter != null) {
                val delegate = AudioPickerDelegate(onPicked, onFailed)
                activeDelegate = delegate
                val picker = UIDocumentPickerViewController(forOpeningContentTypes = listOf(UTTypeAudio), asCopy = true)
                picker.delegate = delegate
                presenter.presentViewController(picker, animated = true, completion = null)
            }
        }
    }

/**
 * Moves the picker's temporary copy into `Caches/imports` so it is not left behind. Failures are logged
 * and return null, which the delegate reports through `onFailed`.
 */
@OptIn(ExperimentalForeignApi::class)
private fun moveIntoImports(source: NSURL): String? {
    val caches = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).firstOrNull() as? String
    if (caches == null) {
        NSLog("Velum import: no caches directory")
        return null
    }
    val dir = "$caches/imports"
    val files = NSFileManager.defaultManager
    val name = sanitizeImportFileName(source.lastPathComponent)
    val dest = "$dir/${(NSDate().timeIntervalSince1970 * 1000).toLong()}_$name"
    return memScoped {
        val error = alloc<ObjCObjectVar<NSError?>>()
        if (!files.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = error.ptr)) {
            NSLog("Velum import: cannot create imports directory: ${error.value?.localizedDescription}")
            return@memScoped null
        }
        if (!files.moveItemAtURL(source, NSURL.fileURLWithPath(dest), error = error.ptr)) {
            NSLog("Velum import: cannot move picked file: ${error.value?.localizedDescription}")
            return@memScoped null
        }
        val size = (files.attributesOfItemAtPath(dest, error = null)?.get(NSFileSize) as? NSNumber)?.longLongValue ?: 0L
        if (size <= 0L) {
            NSLog("Velum import: picked file is empty")
            files.removeItemAtPath(dest, error = null)
            return@memScoped null
        }
        dest
    }
}

private fun topViewController(): UIViewController? {
    val window: UIWindow = UIApplication.sharedApplication.windows.filterIsInstance<UIWindow>()
        .firstOrNull { it.isKeyWindow() } ?: return null
    var top = window.rootViewController
    while (top?.presentedViewController != null) top = top.presentedViewController
    return top
}
