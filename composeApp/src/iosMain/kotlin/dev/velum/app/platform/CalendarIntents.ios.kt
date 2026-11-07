@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.velum.app.platform

import platform.EventKit.EKEvent
import platform.EventKit.EKEventStore
import platform.EventKitUI.EKEventEditViewAction
import platform.EventKitUI.EKEventEditViewController
import platform.EventKitUI.EKEventEditViewDelegateProtocol
import platform.Foundation.NSDate
import platform.Foundation.NSThread
import platform.Foundation.NSTimeZone
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.localTimeZone
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIApplication
import platform.UIKit.UIDevice
import platform.UIKit.UIModalPresentationFullScreen
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.darwin.NSObject

// EventKitUI keeps its delegate weakly. Retain the session through dismissal.
internal var activeCalendarEditor: CalendarEditorSession? = null
    private set

actual fun openCalendarInsert(title: String, description: String): CalendarIntentResult {
    if (!NSThread.isMainThread) return CalendarIntentResult.Blocked("Calendar editor requires the main thread")
    // Apple's permission-free, out-of-process editor requires iOS 17 or later.
    if ((UIDevice.currentDevice.systemVersion.substringBefore('.').toIntOrNull() ?: 0) < 17) {
        return CalendarIntentResult.Blocked("Calendar editor without calendar access requires iOS 17 or later")
    }
    val window = UIApplication.sharedApplication.windows.filterIsInstance<UIWindow>().firstOrNull { it.isKeyWindow() }
    var presenter = window?.rootViewController
    while (presenter?.presentedViewController != null) presenter = presenter.presentedViewController
    val host = presenter
    if (host?.viewIfLoaded?.window == null || host.isBeingDismissed()) {
        return CalendarIntentResult.Blocked("No active presenter for calendar editor")
    }
    return presentCalendarEditor(title, description) { host.presentViewController(it, true, null) }
}

internal fun presentCalendarEditor(
    title: String,
    description: String,
    start: NSDate = NSDate(),
    timeZone: NSTimeZone = NSTimeZone.localTimeZone,
    dismiss: (UIViewController, () -> Unit) -> Unit = { controller, completion ->
        controller.dismissViewControllerAnimated(true, completion)
    },
    present: (EKEventEditViewController) -> Unit,
): CalendarIntentResult {
    if (activeCalendarEditor != null) return CalendarIntentResult.Blocked("Calendar editor is already open")
    val session = CalendarEditorSession(title, description, start, timeZone, dismiss)
    activeCalendarEditor = session
    return try {
        present(session.controller)
        CalendarIntentResult.Launched
    } catch (error: Exception) {
        session.controller.editViewDelegate = null
        activeCalendarEditor = null
        CalendarIntentResult.Blocked("Could not present calendar editor")
    }
}

internal class CalendarEditorSession(
    title: String,
    description: String,
    start: NSDate,
    timeZone: NSTimeZone,
    private val dismiss: (UIViewController, () -> Unit) -> Unit = { controller, completion ->
        controller.dismissViewControllerAnimated(true, completion)
    },
) : NSObject(), EKEventEditViewDelegateProtocol {
    val controller = EKEventEditViewController()
    private var completing = false

    init {
        val store = EKEventStore()
        val event = EKEvent.eventWithEventStore(store)
        event.title = title
        event.notes = description
        event.startDate = start
        event.endDate = NSDate.dateWithTimeIntervalSince1970(start.timeIntervalSince1970 + 3600.0)
        event.timeZone = timeZone
        controller.eventStore = store
        controller.event = event
        controller.editViewDelegate = this
        // Full screen prevents swipe dismissal from bypassing the edit delegate.
        controller.modalPresentationStyle = UIModalPresentationFullScreen
    }

    override fun eventEditViewController(controller: EKEventEditViewController, didCompleteWithAction: EKEventEditViewAction) {
        if (controller !== this.controller || completing) return
        completing = true
        dismiss(controller) {
            controller.editViewDelegate = null
            if (activeCalendarEditor === this) activeCalendarEditor = null
        }
    }
}
