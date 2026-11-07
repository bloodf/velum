@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.velum.app.platform

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSURL
import platform.UIKit.UIDocumentPickerViewController
import platform.UniformTypeIdentifiers.UTTypeAudio
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AudioImporterLifecycleTest {
    private fun picker() = UIDocumentPickerViewController(forOpeningContentTypes = listOf(UTTypeAudio), asCopy = true)

    @Test
    fun cancellationDoesNotImportAndStaleCallbacksCannotReleaseNewOwner() = runTest {
        val events = mutableListOf<String>()
        val old = AudioPickerDelegate({ events += it }, { events += "failed" }, this) { error("Must not copy") }
        val current = AudioPickerDelegate({ events += it }, { events += "failed" }, this)
        val overlap = AudioPickerDelegate({}, {}, this)
        val picker = picker()
        assertTrue(old.activate())
        assertFalse(current.activate())
        old.documentPickerWasCancelled(picker)
        runCurrent()
        assertTrue(current.activate())
        old.documentPickerWasCancelled(picker)
        old.documentPicker(picker, listOf(NSURL.fileURLWithPath("/stale.m4a")))
        runCurrent()
        assertFalse(overlap.activate())
        assertEquals(emptyList(), events)
        current.documentPickerWasCancelled(picker)
        runCurrent()
    }

    @Test
    fun ownerSurvivesCopyAndDuplicateCallbacksDeliverOnlyOnce() = runTest {
        val result = CompletableDeferred<String?>()
        val events = mutableListOf<String>()
        var copies = 0
        val delegate = AudioPickerDelegate({ events += it }, { events += "failed" }, this) {
            copies++
            result.await()
        }
        val next = AudioPickerDelegate({}, {}, this)
        val picker = picker()
        assertTrue(delegate.activate())
        delegate.documentPicker(picker, listOf(NSURL.fileURLWithPath("/picked.m4a")))
        runCurrent()
        assertFalse(next.activate())
        delegate.documentPickerWasCancelled(picker)
        delegate.documentPicker(picker, emptyList<Any>())
        runCurrent()
        assertFalse(next.activate())
        result.complete("/cache/imports/picked.m4a")
        runCurrent()
        assertEquals(1, copies)
        assertEquals(listOf("/cache/imports/picked.m4a"), events)
        assertTrue(next.activate())
        next.documentPickerWasCancelled(picker)
        runCurrent()
    }

    @Test
    fun invalidSelectionAndCopyFailuresReleaseOwnerAndReportOnce() = runTest {
        val picker = picker()
        for (failure in listOf("invalid", "null", "exception")) {
            var failures = 0
            val delegate = AudioPickerDelegate({ error("Must not import") }, { failures++ }, this) {
                if (failure == "exception") error("Copy failed")
                null
            }
            assertTrue(delegate.activate())
            val urls = if (failure == "invalid") emptyList() else listOf(NSURL.fileURLWithPath("/picked.m4a"))
            delegate.documentPicker(picker, urls)
            runCurrent()
            delegate.documentPicker(picker, urls)
            runCurrent()
            assertEquals(1, failures, failure)
        }
    }

    @Test
    fun absentPresenterReportsFailureWithoutTakingOwnership() = runTest {
        var failures = 0
        presentAudioImporter(null, { error("Must not import") }, { failures++ })
        assertEquals(1, failures)
        val next = AudioPickerDelegate({}, {}, this)
        assertTrue(next.activate())
        next.documentPickerWasCancelled(picker())
        runCurrent()
    }
}
