package dev.velum.app.platform

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import dev.velum.app.R
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class MicRecordingServiceRuntimeTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var service: MicRecordingService
    private lateinit var runtime: FakeRuntime
    private lateinit var root: File
    private var startId = 0

    @Before fun prepare() {
        MicRecordingService.resetIdle()
        MicRecordingService.pcmListener = null
        root = Files.createTempDirectory("velum-mic-fgs-test").toFile()
        service = Robolectric.buildService(MicRecordingService::class.java).create().get()
        runtime = FakeRuntime()
        service.runtime = runtime
        grant()
    }
    @After fun cleanup() {
        command(MicRecordingService.ACTION_STOP)
        runtime.runIfQueued("velum-mic-stop")
        service.onDestroy()
        runtime.runIfQueued("velum-mic-destroy-stop")
        MicRecordingService.pcmListener = null
        MicRecordingService.resetIdle()
        root.deleteRecursively()
    }
    private fun grant() = shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
    private fun deny() = shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
    private fun command(action: String, path: String = File(root, "synthetic.wav").absolutePath) =
        service.onStartCommand(Intent(app, MicRecordingService::class.java).setAction(action)
            .putExtra(MicRecordingService.EXTRA_PATH, if (action == MicRecordingService.ACTION_START) path else MicRecordingService.state.value.session?.absolutePath ?: path)
            .putExtra(MicRecordingService.EXTRA_SESSION_TOKEN, if (action == MicRecordingService.ACTION_START) 0L else MicRecordingService.state.value.session?.token ?: -1L), 0, ++startId)
    private fun failure(permission: Boolean = false): MicSessionState.Failed {
        val state = assertIs<MicSessionState.Failed>(MicRecordingService.state.value)
        assertEquals(permission, state.permissionRequired)
        assertFalse(runtime.foreground)
        assertTrue(shadowOf(service).isStoppedBySelf)
        return state
    }

    @Test fun denied_at_start_never_promotes_or_allocates_audio_or_creates_a_file() {
        deny(); command(MicRecordingService.ACTION_START)
        assertEquals(service.getString(R.string.mic_recording_permission_required), failure(true).message)
        assertEquals(0, runtime.promotions); assertEquals(0, runtime.creations); assertTrue(runtime.workers.isEmpty())
        assertFalse(File(root, "synthetic.wav").exists())
    }
    @Test fun revoked_between_check_and_promotion_is_caught_and_pending_service_is_stopped() {
        runtime.promoteAction = { deny(); throw SecurityException("synthetic private detail") }
        command(MicRecordingService.ACTION_START)
        failure(true); assertEquals(1, runtime.promotions); assertEquals(0, runtime.creations)
        assertTrue(runtime.workers.isEmpty())
    }
    @Test fun promotion_security_failure_with_permission_granted_is_a_localized_start_failure() {
        runtime.promoteAction = { throw SecurityException("synthetic private detail") }
        command(MicRecordingService.ACTION_START)
        assertEquals(service.getString(R.string.mic_recording_start_failed), failure().message)
        assertEquals(0, runtime.creations)
    }
    @Test fun foreground_start_restriction_is_caught_without_falling_back_to_another_service_type() {
        runtime.promoteAction = { throw IllegalStateException("synthetic background start restriction") }
        command(MicRecordingService.ACTION_START)
        failure(); assertEquals(listOf(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE), runtime.types)
        assertTrue(runtime.workers.isEmpty())
    }
    @Test fun revocation_after_promotion_is_rechecked_before_audio_initialization() {
        command(MicRecordingService.ACTION_START); deny(); runtime.run("velum-mic-init")
        failure(true); assertEquals(0, runtime.creations); assertFalse(File(root, "synthetic.wav").exists())
    }
    @Test fun audio_constructor_security_failure_cleans_foreground_without_publishing_recording() {
        runtime.createAction = { throw SecurityException("synthetic audio denial") }
        command(MicRecordingService.ACTION_START); runtime.run("velum-mic-init")
        failure(true); assertFalse(runtime.has("velum-mic-fgs"))
    }
    @Test fun uninitialized_input_is_released_and_never_started() {
        runtime.input.initialized = false
        command(MicRecordingService.ACTION_START); runtime.run("velum-mic-init")
        failure(); assertEquals(0, runtime.input.starts); assertEquals(1, runtime.input.releases)
        assertFalse(File(root, "synthetic.wav").exists())
    }
    @Test fun revoked_during_audio_start_stops_and_releases_the_partially_started_input() {
        runtime.input.startAction = { throw SecurityException("synthetic revoked start") }
        command(MicRecordingService.ACTION_START); runtime.run("velum-mic-init")
        failure(true); assertEquals(1, runtime.input.stops); assertEquals(1, runtime.input.releases)
        assertFalse(runtime.has("velum-mic-fgs"))
    }
    @Test fun start_that_does_not_enter_recording_is_a_failure_instead_of_fake_success() {
        runtime.input.startAction = { runtime.input.recording = false }
        command(MicRecordingService.ACTION_START); runtime.run("velum-mic-init")
        failure(); assertEquals(1, runtime.input.releases); assertFalse(runtime.has("velum-mic-fgs"))
    }
    @Test fun output_creation_failure_releases_input_without_starting_audio() {
        val parent = File(root, "not-a-directory").apply { writeText("synthetic") }
        command(MicRecordingService.ACTION_START, File(parent, "out.wav").absolutePath)
        runtime.run("velum-mic-init")
        failure(); assertEquals(0, runtime.input.starts); assertEquals(1, runtime.input.releases)
    }
    @Test fun negative_read_fails_and_releases_audio_and_foreground_notification() {
        runtime.input.readAction = { -3 }
        command(MicRecordingService.ACTION_START); runtime.run("velum-mic-init"); runtime.run("velum-mic-fgs")
        assertEquals(service.getString(R.string.mic_recording_capture_failed), failure().message)
        assertEquals(1, runtime.input.stops); assertEquals(1, runtime.input.releases)
    }
    @Test fun revoked_during_read_is_a_localized_permission_failure_and_releases_audio() {
        runtime.input.readAction = { throw SecurityException("synthetic revoked read") }
        command(MicRecordingService.ACTION_START); runtime.run("velum-mic-init"); runtime.run("velum-mic-fgs")
        failure(true); assertEquals(1, runtime.input.releases)
    }
    @Test fun stop_while_initialization_is_queued_prevents_audio_and_preserves_terminal_cancellation() {
        command(MicRecordingService.ACTION_START); command(MicRecordingService.ACTION_STOP)
        runtime.run("velum-mic-stop"); val stopped = failure()
        runtime.run("velum-mic-init")
        assertEquals(stopped, MicRecordingService.state.value); assertEquals(0, runtime.creations)
    }
    @Test fun stale_initializer_cannot_touch_a_newer_session() {
        command(MicRecordingService.ACTION_START); command(MicRecordingService.ACTION_STOP); runtime.run("velum-mic-stop")
        val next = File(root, "second.wav").absolutePath
        command(MicRecordingService.ACTION_START, next)
        runtime.run("velum-mic-init"); assertEquals(0, runtime.creations)
        runtime.run("velum-mic-init"); assertEquals(next, assertIs<MicSessionState.Recording>(MicRecordingService.state.value).absolutePath)
        assertTrue(runtime.foreground); assertEquals(1, runtime.creations)
    }
    @Test fun duplicate_start_does_not_clobber_the_active_recording_or_promote_twice() {
        command(MicRecordingService.ACTION_START); runtime.run("velum-mic-init")
        val state = MicRecordingService.state.value
        command(MicRecordingService.ACTION_START, File(root, "duplicate.wav").absolutePath)
        assertEquals(state, MicRecordingService.state.value); assertEquals(1, runtime.promotions)
    }
    @Test fun normal_granted_capture_pause_resume_and_stop_finalize_only_synthetic_pcm() {
        command(MicRecordingService.ACTION_START)
        assertEquals(1, runtime.promotions); assertEquals(0, runtime.creations)
        runtime.run("velum-mic-init"); assertIs<MicSessionState.Recording>(MicRecordingService.state.value)
        command(MicRecordingService.ACTION_PAUSE); assertIs<MicSessionState.Paused>(MicRecordingService.state.value)
        command(MicRecordingService.ACTION_RESUME); assertIs<MicSessionState.Recording>(MicRecordingService.state.value)
        assertEquals(1, runtime.input.starts); assertEquals(0, runtime.input.stops)
        var reads = 0
        runtime.input.readAction = { buffer ->
            if (reads++ == 0) { buffer[0] = 1; buffer[1] = 2; buffer[2] = 3; buffer[3] = 4; 4 }
            else { command(MicRecordingService.ACTION_STOP); runtime.run("velum-mic-stop"); 0 }
        }
        runtime.run("velum-mic-fgs")
        val stopped = assertIs<MicSessionState.Stopped>(MicRecordingService.state.value)
        assertEquals(48L, stopped.sizeBytes); assertEquals(48L, File(stopped.absolutePath).length())
        assertEquals(1, runtime.input.stops); assertEquals(1, runtime.input.releases); assertFalse(runtime.foreground)
    }
    @Test @Config(qualifiers = "pt-rBR") fun permission_failure_uses_portuguese_resources() {
        deny(); command(MicRecordingService.ACTION_START)
        assertEquals("A permissão do microfone é necessária para gravar.", failure(true).message)
    }
    @Test fun destroy_during_pending_initialization_cancels_and_removes_foreground() {
        command(MicRecordingService.ACTION_START); service.onDestroy(); runtime.run("velum-mic-destroy-stop")
        runtime.run("velum-mic-init"); failure(); assertEquals(0, runtime.creations)
    }

    @Test fun a_destroyed_service_initializer_cannot_overwrite_a_new_service_instances_state() {
        command(MicRecordingService.ACTION_START)
        val previousService = service; val previousRuntime = runtime
        previousService.onDestroy()
        service = Robolectric.buildService(MicRecordingService::class.java).create().get()
        runtime = FakeRuntime(); service.runtime = runtime
        val next = File(root, "next-instance.wav").absolutePath
        command(MicRecordingService.ACTION_START, next); runtime.run("velum-mic-init")
        previousRuntime.run("velum-mic-init"); previousRuntime.run("velum-mic-destroy-stop")
        assertEquals(next, assertIs<MicSessionState.Recording>(MicRecordingService.state.value).absolutePath)
        assertTrue(runtime.foreground); assertFalse(previousRuntime.foreground); assertEquals(0, previousRuntime.creations)
    }
    @Test fun capture_failure_clears_the_pcm_callback_as_well_as_audio_and_notification() {
        runtime.input.readAction = { 4 }
        command(MicRecordingService.ACTION_START); runtime.run("velum-mic-init")
        MicRecordingService.attachPcm(MicRecordingService.state.value.session!!) { error("synthetic callback failure") }
        runtime.run("velum-mic-fgs")
        failure(); assertEquals(null, MicRecordingService.pcmListener); assertEquals(1, runtime.input.releases)
        val bytes = File(root, "synthetic.wav").readBytes()
        assertEquals(4, java.nio.ByteBuffer.wrap(bytes, 40, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int)
    }

    @Test fun an_old_reader_finalizes_its_own_byte_count_after_a_new_session_starts() {
        val oldPath = File(root, "old-session.wav").absolutePath
        val nextPath = File(root, "new-session.wav").absolutePath
        command(MicRecordingService.ACTION_START, oldPath); runtime.run("velum-mic-init")
        var reads = 0
        runtime.input.readAction = {
            if (reads++ == 0) 4 else {
                command(MicRecordingService.ACTION_STOP); runtime.run("velum-mic-stop")
                runtime.createAction = { FakeInput() }
                command(MicRecordingService.ACTION_START, nextPath); runtime.run("velum-mic-init")
                0
            }
        }
        runtime.run("velum-mic-fgs")
        val bytes = File(oldPath).readBytes()
        val dataSize = java.nio.ByteBuffer.wrap(bytes, 40, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
        assertEquals(4, dataSize)
        assertEquals(nextPath, assertIs<MicSessionState.Recording>(MicRecordingService.state.value).absolutePath)
        assertTrue(runtime.foreground)
    }

    @Test fun existing_output_is_not_truncated_or_opened_for_capture() {
        val output = File(root, "synthetic.wav")
        WavWriter(output.path).apply { writePcm(ByteArray(32) { 7 }) }.finish()
        val before = output.readBytes()
        command(MicRecordingService.ACTION_START); runtime.run("velum-mic-init")
        failure(); kotlin.test.assertContentEquals(before, output.readBytes()); assertEquals(0, runtime.creations)
    }
    @Test fun output_created_between_precheck_and_reservation_wins_without_being_overwritten() {
        val output = File(root, "synthetic.wav"); var before = byteArrayOf()
        runtime.createAction = {
            WavWriter(output.path).apply { writePcm(ByteArray(32) { 9 }) }.finish()
            before = output.readBytes(); runtime.input
        }
        command(MicRecordingService.ACTION_START); runtime.run("velum-mic-init")
        failure(); kotlin.test.assertContentEquals(before, output.readBytes())
        assertEquals(0, runtime.input.starts); assertEquals(1, runtime.input.releases)
    }
    @Test fun platform_foreground_adapter_posts_then_removes_the_notification_without_allocating_audio() {
        runtime.platformForeground = true
        command(MicRecordingService.ACTION_START)
        assertEquals(MicRecordingService.NOTIFICATION_ID, shadowOf(service).lastForegroundNotificationId)
        assertEquals(0, runtime.creations)
        command(MicRecordingService.ACTION_STOP); runtime.run("velum-mic-stop"); runtime.run("velum-mic-init")
        val notifications = service.getSystemService(android.app.NotificationManager::class.java).activeNotifications
        assertTrue(notifications.none { it.id == MicRecordingService.NOTIFICATION_ID })
        assertEquals(0, runtime.creations)
    }

    @Test fun commands_with_another_path_or_token_cannot_stop_pause_or_mark_the_active_capture() {
        command(MicRecordingService.ACTION_START); runtime.run("velum-mic-init")
        val session = MicRecordingService.state.value.session!!
        for (wrong in listOf(session.copy(token = session.token - 1), session.copy(absolutePath = File(root, "other.wav").path))) {
            for (action in listOf(MicRecordingService.ACTION_STOP, MicRecordingService.ACTION_PAUSE, MicRecordingService.ACTION_RESUME, MicRecordingService.ACTION_MARK)) {
                service.onStartCommand(Intent(app, MicRecordingService::class.java).setAction(action)
                    .putExtra(MicRecordingService.EXTRA_SESSION_TOKEN, wrong.token).putExtra(MicRecordingService.EXTRA_PATH, wrong.absolutePath), 0, ++startId)
            }
        }
        assertIs<MicSessionState.Recording>(MicRecordingService.state.value)
        assertFalse(runtime.has("velum-mic-stop")); assertTrue(MicRecordingService.snapshotMarkers().isEmpty())
        assertEquals(0, runtime.input.stops); assertTrue(runtime.foreground)
    }
    @Test fun a_delayed_notification_or_cancel_from_an_old_generation_cannot_stop_a_reused_path() {
        command(MicRecordingService.ACTION_START); runtime.run("velum-mic-init")
        val old = MicRecordingService.state.value.session!!
        val oldStop = shadowOf(runtime.notifications.last().actions[1].actionIntent).savedIntent
        assertEquals(old.token, oldStop.getLongExtra(MicRecordingService.EXTRA_SESSION_TOKEN, -1))
        command(MicRecordingService.ACTION_STOP); runtime.run("velum-mic-stop")
        val source = File(old.absolutePath); val archived = File(root, "prior-session.wav")
        assertTrue(source.renameTo(archived)); val bytes = archived.readBytes()
        val input = FakeInput(); runtime.createAction = { input }
        command(MicRecordingService.ACTION_START); runtime.run("velum-mic-init")
        val current = MicRecordingService.state.value.session!!
        assertFalse(current.token == old.token); assertEquals(current.absolutePath, old.absolutePath)
        service.onStartCommand(oldStop, 0, ++startId)
        MicRecordingService.cancelPendingStart(app, old)
        assertEquals(current, MicRecordingService.state.value.session); assertIs<MicSessionState.Recording>(MicRecordingService.state.value)
        assertFalse(runtime.has("velum-mic-stop")); assertEquals(0, input.stops); assertTrue(runtime.foreground)
        kotlin.test.assertContentEquals(bytes, archived.readBytes())
    }
    @Test fun cancellation_before_start_delivery_prevents_foreground_and_audio_initialization() {
        val session = MicRecordingService.newSession(File(root, "cancelled.wav").path)
        MicRecordingService.cancelPendingStart(app, session)
        service.onStartCommand(Intent(app, MicRecordingService::class.java).setAction(MicRecordingService.ACTION_START)
            .putExtra(MicRecordingService.EXTRA_SESSION_TOKEN, session.token).putExtra(MicRecordingService.EXTRA_PATH, session.absolutePath), 0, ++startId)
        assertEquals(0, runtime.promotions); assertEquals(0, runtime.creations); assertTrue(runtime.workers.isEmpty())
        assertFalse(File(session.absolutePath).exists()); assertTrue(shadowOf(service).isStoppedBySelf)
    }
    @Test fun a_foreign_subscription_or_disposal_cannot_replace_or_clear_the_owned_callback() {
        command(MicRecordingService.ACTION_START); runtime.run("velum-mic-init")
        val session = MicRecordingService.state.value.session!!; val listener: (ByteArray) -> Unit = {}
        assertTrue(MicRecordingService.attachPcm(session, listener))
        assertFalse(MicRecordingService.attachPcm(session.copy(token = session.token - 1)) { error("Foreign callback") })
        MicRecordingService.detachPcm(session.copy(token = session.token - 1), listener)
        assertTrue(MicRecordingService.pcmListener === listener)
        MicRecordingService.detachPcm(session, listener); assertEquals(null, MicRecordingService.pcmListener)
    }

    @Test fun repeated_matching_stop_keeps_terminal_identity_and_releases_input_once() {
        command(MicRecordingService.ACTION_START); runtime.run("velum-mic-init")
        var reads = 0
        runtime.input.readAction = { buffer ->
            if (reads++ == 0) { buffer[0] = 1; buffer[1] = 2; 2 }
            else { command(MicRecordingService.ACTION_STOP); runtime.run("velum-mic-stop"); 0 }
        }
        runtime.run("velum-mic-fgs")
        val stopped = assertIs<MicSessionState.Stopped>(MicRecordingService.state.value)
        val bytes = File(stopped.absolutePath).readBytes()
        repeat(2) { command(MicRecordingService.ACTION_STOP); runtime.run("velum-mic-stop") }
        assertEquals(stopped, MicRecordingService.state.value)
        assertEquals(1, runtime.input.stops); assertEquals(1, runtime.input.releases)
        kotlin.test.assertContentEquals(bytes, File(stopped.absolutePath).readBytes())
    }

    private class FakeRuntime : MicServiceRuntime {
        val workers = mutableListOf<Pair<String, () -> Unit>>()
        val input = FakeInput()
        var foreground = false
        var platformForeground = false
        var promotions = 0
        var creations = 0
        val types = mutableListOf<Int>()
        val notifications = mutableListOf<Notification>()
        var promoteAction: () -> Unit = {}
        var createAction: () -> MicAudioInput = { input }
        override fun promote(service: Service, notification: Notification, type: Int) {
            promotions++; types += type; notifications += notification; promoteAction()
            if (platformForeground) AndroidMicServiceRuntime.promote(service, notification, type)
            foreground = true
        }
        override fun removeForeground(service: Service) {
            if (platformForeground) AndroidMicServiceRuntime.removeForeground(service)
            foreground = false
        }
        override fun createInput(source: Int): MicAudioInput { creations++; return createAction() }
        override fun launch(name: String, block: () -> Unit): Thread? { workers += name to block; return null }
        fun has(name: String) = workers.any { it.first == name }
        fun run(name: String) { val index = workers.indexOfFirst { it.first == name }; check(index >= 0); workers.removeAt(index).second() }
        fun runIfQueued(name: String) { if (has(name)) run(name) }
    }
    private class FakeInput : MicAudioInput {
        override var initialized = true
        override var recording = false
        override val bufferSize = 32
        var starts = 0
        var stops = 0
        var releases = 0
        var startAction: () -> Unit = { recording = true }
        var readAction: (ByteArray) -> Int = { -3 }
        override fun start() { starts++; startAction() }
        override fun read(buffer: ByteArray) = readAction(buffer)
        override fun stop() { stops++; recording = false }
        override fun release() { releases++ }
    }
}
