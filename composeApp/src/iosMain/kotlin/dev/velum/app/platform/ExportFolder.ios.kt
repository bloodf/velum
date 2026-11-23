@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.velum.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.cinterop.*
import platform.Foundation.*
import platform.UIKit.*
import platform.UniformTypeIdentifiers.UTTypeFolder
import platform.darwin.NSObject

actual object ExportFolderStore {
    private const val KEY = "velum_export_folder_bookmark"

    internal fun remember(url: NSURL): Boolean = withExportFolderAccess(url) {
        val bookmark = url.bookmarkDataWithOptions(0u, null, null, null) ?: return@withExportFolderAccess false
        NSUserDefaults.standardUserDefaults.setObject(bookmark, forKey = KEY)
        true
    } ?: false

    internal fun resolve(): NSURL? = memScoped {
        val bookmark = NSUserDefaults.standardUserDefaults.dataForKey(KEY) ?: return@memScoped null
        val stale = alloc<BooleanVar>()
        val url = NSURL.URLByResolvingBookmarkData(bookmark, 0u, null, stale.ptr, null)
            ?: return@memScoped null
        if (stale.value && !remember(url)) return@memScoped null
        url
    }

    actual fun currentUri(): String? = resolve()?.absoluteString

    actual fun clear() {
        NSUserDefaults.standardUserDefaults.removeObjectForKey(KEY)
    }
}

// UIDocumentPicker retains its delegate weakly.
private var exportPickerDelegate: ExportPickerDelegate? = null

private class ExportPickerDelegate(val onPicked: (String) -> Unit) : NSObject(),
    UIDocumentPickerDelegateProtocol, UIAdaptivePresentationControllerDelegateProtocol {
    fun release(controller: UIDocumentPickerViewController) {
        controller.delegate = null
        controller.presentationController?.delegate = null
        if (exportPickerDelegate === this) exportPickerDelegate = null
    }

    override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
        release(controller)
        val url = didPickDocumentsAtURLs.firstOrNull() as? NSURL
        if (url != null && ExportFolderStore.remember(url)) {
            url.absoluteString?.let(onPicked)
        } else {
            showExportFolderError("Could not save access to this folder. Choose it again.")
        }
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) = release(controller)

    override fun presentationControllerDidDismiss(presentationController: UIPresentationController) {
        (presentationController.presentedViewController as? UIDocumentPickerViewController)?.let(::release)
    }
}

@Composable
actual fun rememberExportFolderPicker(onPicked: (treeUri: String) -> Unit): () -> Unit = remember(onPicked) {
    {
        val presenter = exportFolderPresenter()
        if (presenter != null && exportPickerDelegate == null) {
            val delegate = ExportPickerDelegate(onPicked)
            val picker = UIDocumentPickerViewController(forOpeningContentTypes = listOf(UTTypeFolder), asCopy = false)
            picker.allowsMultipleSelection = false
            picker.delegate = delegate
            exportPickerDelegate = delegate
            presenter.presentViewController(picker, animated = true) {
                picker.presentationController?.delegate = delegate
            }
        }
    }
}

private fun exportFolderPresenter(): UIViewController? {
    val window = UIApplication.sharedApplication.windows.filterIsInstance<UIWindow>()
        .firstOrNull { it.isKeyWindow() } ?: return null
    var top = window.rootViewController
    while (top?.presentedViewController != null) top = top.presentedViewController
    return top
}

private fun showExportFolderError(message: String) {
    val alert = UIAlertController.alertControllerWithTitle("Export folder", message, UIAlertControllerStyleAlert)
    alert.addAction(UIAlertAction.actionWithTitle("OK", UIAlertActionStyleDefault, null))
    exportFolderPresenter()?.presentViewController(alert, animated = true, completion = null)
}

internal fun isExportFilenameLeaf(filename: String): Boolean = filename.isNotBlank() &&
    filename != "." && filename != ".." && filename.none { it == '/' || it == '\\' || it.code < 32 || it.code == 127 }

private inline fun <T> withExportFolderAccess(url: NSURL, action: () -> T): T? {
    if (!url.startAccessingSecurityScopedResource()) return null
    return try { action() } finally { url.stopAccessingSecurityScopedResource() }
}

actual fun writeMarkdownToExportFolder(filename: String, markdown: String): ExportFolderResult {
    if (!isExportFilenameLeaf(filename)) return ExportFolderResult.Failed("Invalid export filename")
    val folder = ExportFolderStore.resolve() ?: return ExportFolderResult.Failed("No export folder selected or folder access expired")
    return withExportFolderAccess(folder) { writeIosExportMarkdown(folder, filename, markdown) }
        ?: ExportFolderResult.Failed("Could not access export folder. Choose it again.")
}

internal fun writeIosExportMarkdown(folder: NSURL, filename: String, markdown: String): ExportFolderResult {
    if (!isExportFilenameLeaf(filename)) return ExportFolderResult.Failed("Invalid export filename")
    val target = folder.URLByAppendingPathComponent(filename, isDirectory = false)
        ?: return ExportFolderResult.Failed("Could not create export URL")
    val path = target.path ?: return ExportFolderResult.Failed("Invalid export URL")
    val files = NSFileManager.defaultManager
    if (files.attributesOfItemAtPath(path, null)?.get(NSFileType) == NSFileTypeSymbolicLink) {
        return ExportFolderResult.Failed("Export target is a symbolic link")
    }
    return memScoped {
        val error = alloc<ObjCObjectVar<NSError?>>()
        val text = NSString.create(string = markdown)
        if (text.writeToURL(target, atomically = true, encoding = NSUTF8StringEncoding, error = error.ptr)) {
            ExportFolderResult.Ok(filename)
        } else {
            ExportFolderResult.Failed("Could not write Markdown (${error.value?.domain}:${error.value?.code})")
        }
    }
}
