package dev.velum.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.CancellationException
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

/** The picker only keeps a weak delegate reference, so hold it through copying or cancellation. */
private var activeDelegate: AudioPickerDelegate? = null
private val importScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

internal class AudioPickerDelegate(
    private val onPicked: (String) -> Unit,
    private val onFailed: () -> Unit,
    private val scope: CoroutineScope = importScope,
    private val copy: suspend (NSURL) -> String? = { url -> withContext(Dispatchers.IO) { moveIntoImports(url) } },
) : NSObject(), UIDocumentPickerDelegateProtocol {
    private var completed = false

    // Called on Main, like every mutation of the active session and its terminal state.
    internal fun activate(): Boolean {
        if (activeDelegate != null || completed) return false
        activeDelegate = this
        return true
    }

    private fun release() {
        if (activeDelegate === this) activeDelegate = null
    }

    override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
        scope.launch {
            if (activeDelegate !== this@AudioPickerDelegate || completed) return@launch
            completed = true
            val url = didPickDocumentsAtURLs.firstOrNull() as? NSURL
            val path = try {
                if (url == null) null else copy(url)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                NSLog("Velum import: cannot copy picked file")
                null
            } finally {
                release()
            }
            if (path != null) onPicked(path) else onFailed()
        }
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        scope.launch {
            if (activeDelegate !== this@AudioPickerDelegate || completed) return@launch
            completed = true
            release()
        }
    }
}

/** Opens the Files picker for audio. `asCopy` gives an app-owned copy, so no security-scoped access is needed. */
@Composable
actual fun rememberAudioImporter(onFailed: () -> Unit, onPicked: (path: String) -> Unit): () -> Unit =
    remember(onPicked, onFailed) {
        {
            importScope.launch {
                presentAudioImporter(topViewController(), onPicked, onFailed)
            }
            Unit
        }
    }

internal fun presentAudioImporter(presenter: UIViewController?, onPicked: (String) -> Unit, onFailed: () -> Unit) {
    if (activeDelegate != null) return
    if (presenter == null || presenter.isBeingDismissed() || presenter is UIDocumentPickerViewController) {
        onFailed()
        return
    }
    val delegate = AudioPickerDelegate(onPicked, onFailed)
    if (!delegate.activate()) return
    val picker = UIDocumentPickerViewController(forOpeningContentTypes = listOf(UTTypeAudio), asCopy = true)
    picker.delegate = delegate
    presenter.presentViewController(picker, animated = true, completion = null)
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
            NSLog("Velum import: cannot create imports directory (${errorCode(error.value)})")
            return@memScoped null
        }
        if (!files.moveItemAtURL(source, NSURL.fileURLWithPath(dest), error = error.ptr)) {
            NSLog("Velum import: cannot move picked file (${errorCode(error.value)})")
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

/** Domain and code only: the description can carry the user's file name. */
private fun errorCode(error: NSError?): String = "${error?.domain}:${error?.code}"

private fun topViewController(): UIViewController? {
    val window: UIWindow = UIApplication.sharedApplication.windows.filterIsInstance<UIWindow>()
        .firstOrNull { it.isKeyWindow() } ?: return null
    var top = window.rootViewController
    while (top?.presentedViewController != null) top = top.presentedViewController
    return top
}

@OptIn(ExperimentalForeignApi::class)
actual fun deleteImportCopies(): Boolean {
    val caches = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).firstOrNull() as? String ?: return true
    val files = NSFileManager.defaultManager
    files.removeItemAtPath("$caches/imports", error = null)
    return !files.fileExistsAtPath("$caches/imports")
}

/** iOS shares text only; nothing is kept in a cache. */
actual fun deleteShareExports(): Boolean = true
