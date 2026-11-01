package dev.velum.app.platform

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import dev.velum.app.R
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AndroidMicCaptureRuntimeTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val sessions = MutableStateFlow<MicSessionState>(MicSessionState.Idle)
    private var launches = 0
    private var stops = 0
    private var launch: (MicSessionIdentity) -> Unit = {}
    private val path = "/synthetic-test-only/not-created.wav"
    private fun capture() = AndroidMicCapture(
        contextProvider = { app }, sessions = sessions,
        launchService = { _, session, _ -> launches++; launch(session) }, requestStop = { _, _ -> stops++ },
    )
    @Before fun prepare() { shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO); MicRecordingService.resetIdle() }

    @Test fun denied_at_caller_does_not_deliver_any_service_command() = runTest {
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        assertEquals(MicStartResult.PermissionRequired, capture().start(path)); assertEquals(0, launches)
    }
    @Test fun security_exception_while_delivering_start_after_revocation_returns_permission_required() = runTest {
        launch = { shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO); throw SecurityException("synthetic private detail") }
        assertEquals(MicStartResult.PermissionRequired, capture().start(path)); assertEquals(1, launches)
    }
    @Test fun background_start_restriction_returns_localized_failure_without_leaking_exception_details() = runTest {
        launch = { throw IllegalStateException("synthetic private detail") }
        val failed = assertIs<MicStartResult.Failed>(capture().start(path))
        assertEquals(app.getString(R.string.mic_recording_start_failed), failed.message)
        assertFalse(failed.message.contains("synthetic"))
    }
    @Test fun security_failure_with_permission_still_granted_does_not_claim_permission_was_denied() = runTest {
        launch = { throw SecurityException("synthetic while-in-use restriction") }
        assertIs<MicStartResult.Failed>(capture().start(path))
    }
    @Test fun service_permission_failure_is_mapped_to_permission_required() = runTest {
        launch = { session -> sessions.value = MicSessionState.Failed(app.getString(R.string.mic_recording_permission_required), true, session) }
        assertEquals(MicStartResult.PermissionRequired, capture().start(path)); assertEquals(1, stops)
    }
    @Test fun recording_requires_a_matching_output_path_and_a_real_recording_state() = runTest {
        launch = { session -> sessions.value = MicSessionState.Recording(path, 0, session.token) }
        val capture = capture()
        assertIs<MicStartResult.Started>(capture.start(path)); assertTrue(capture.isCapturing)
    }
    @Test fun a_different_sessions_recording_is_not_reported_as_success() = runTest {
        launch = { session -> sessions.value = MicSessionState.Recording("/synthetic-other.wav", 0, session.token) }
        assertIs<MicStartResult.Failed>(capture().start(path)); assertEquals(1, stops)
    }
    @Test fun cancellation_while_waiting_stops_the_owned_pending_start_and_propagates() = runTest {
        val capture = capture()
        val pending = async(start = CoroutineStart.UNDISPATCHED) { capture.start(path) }
        assertEquals(1, launches); pending.cancelAndJoin()
        assertTrue(pending.isCancelled); assertEquals(1, stops); assertFalse(capture.isCapturing)
    }
    @Test fun cancellation_during_delivery_also_requests_cleanup_and_propagates() = runTest {
        launch = { throw CancellationException("synthetic cancellation") }
        val pending = async(start = CoroutineStart.UNDISPATCHED) { capture().start(path) }
        pending.join(); assertTrue(pending.isCancelled); assertEquals(1, stops)
    }
    @Test fun concurrent_starts_cannot_reset_the_first_pending_start_or_launch_twice() = runTest {
        val capture = capture()
        val first = async(start = CoroutineStart.UNDISPATCHED) { capture.start(path) }
        assertIs<MicStartResult.Failed>(capture.start("/synthetic-second.wav")); assertEquals(1, launches)
        first.cancelAndJoin(); assertEquals(1, stops)
    }
    @Test fun timeout_cancels_the_pending_start_instead_of_leaving_an_orphan_service() = runTest {
        val pending = async(start = CoroutineStart.UNDISPATCHED) { capture().start(path) }
        advanceTimeBy(8_001); runCurrent()
        assertIs<MicStartResult.Failed>(pending.await()); assertEquals(1, stops)
    }
    @Test fun stop_returns_an_existing_capture_failure_instead_of_not_capturing_or_success() = runTest {
        val capture = capture(); sessions.value = MicSessionState.Failed(app.getString(R.string.mic_recording_capture_failed), session = MicSessionIdentity(1, path))
        assertEquals(MicStopResult.Failed(app.getString(R.string.mic_recording_capture_failed)), capture.stop())
        assertEquals(0, stops)
    }
    @Test fun non_owner_stop_does_not_deliver_a_service_command_or_consume_a_terminal_result() = runTest {
        val a = MicSessionIdentity(100, path); val b = MicSessionIdentity(101, "/synthetic/B.wav")
        sessions.value = MicSessionState.Recording(path, 32, a.token)
        var commands = 0
        val client = AndroidMicCapture({ app }, sessions, stopService = { _, _ -> commands++ })
        assertEquals(MicStopResult.NotCapturing, client.stop(b)); assertEquals(0, commands)
        sessions.value = MicSessionState.Stopped(path, 0, 76, a.token)
        assertEquals(MicStopResult.NotCapturing, client.stop(b)); assertEquals(a, sessions.value.session)
        assertEquals(a, assertIs<MicStopResult.Stopped>(client.stop(a)).session); assertEquals(0, commands)
        assertEquals(a, sessions.value.session)
    }
    @Test fun an_old_stop_waiter_cannot_consume_or_reset_a_newer_sessions_completion() = runTest {
        val a = MicSessionIdentity(100, path); val b = MicSessionIdentity(101, path)
        sessions.value = MicSessionState.Recording(path, 32, a.token)
        val commands = mutableListOf<MicSessionIdentity>()
        val client = AndroidMicCapture({ app }, sessions, stopService = { _, session -> commands += session })
        val old = async(start = CoroutineStart.UNDISPATCHED) { client.stop(a) }
        assertEquals(listOf(a), commands)
        sessions.value = MicSessionState.Stopped(path, 0, 108, b.token)
        assertEquals(MicStopResult.NotCapturing, old.await()); assertEquals(b, sessions.value.session)
        assertEquals(b, assertIs<MicStopResult.Stopped>(client.stop(b)).session)
    }
    @Test fun cancellation_of_a_stop_wait_preserves_identified_terminal_state_for_retry() = runTest {
        val a = MicSessionIdentity(100, path); sessions.value = MicSessionState.Recording(path, 32, a.token)
        var commands = 0
        val client = AndroidMicCapture({ app }, sessions, stopService = { _, _ -> commands++ })
        val first = async(start = CoroutineStart.UNDISPATCHED) { client.stop(a) }
        first.cancelAndJoin(); assertEquals(a, sessions.value.session)
        sessions.value = MicSessionState.Stopped(path, 0, 76, a.token)
        assertEquals(a, assertIs<MicStopResult.Stopped>(client.stop(a)).session)
        assertEquals(1, commands); assertEquals(a, sessions.value.session)
    }

    @Test fun marker_timeout_returns_localized_failure_without_inventing_a_marker() = runTest {
        val a = MicSessionIdentity(100, path); sessions.value = MicSessionState.Recording(path, 32, a.token)
        val pending = async(start = CoroutineStart.UNDISPATCHED) { capture().addMarker(a) }
        val intent = shadowOf(app).nextStartedService!!
        assertEquals(a.token, intent.getLongExtra(MicRecordingService.EXTRA_SESSION_TOKEN, -1))
        assertEquals(a.absolutePath, intent.getStringExtra(MicRecordingService.EXTRA_PATH))
        advanceTimeBy(1_001); runCurrent()
        assertEquals(app.getString(R.string.mic_recording_capture_failed), assertIs<MicMarkerResult.Failed>(pending.await()).message)
        assertTrue(MicRecordingService.snapshotMarkers().isEmpty())
    }
    @Test fun another_sessions_controls_do_not_deliver_commands() = runTest {
        val a = MicSessionIdentity(100, path); val b = MicSessionIdentity(101, path)
        sessions.value = MicSessionState.Recording(path, 32, a.token)
        val client = capture()
        assertEquals(MicPauseResult.NotCapturing, client.pause(b))
        assertEquals(MicPauseResult.NotCapturing, client.resume(b))
        assertEquals(MicMarkerResult.NotCapturing, client.addMarker(b))
        assertEquals(null, shadowOf(app).nextStartedService); assertEquals(a, sessions.value.session)
    }

}
