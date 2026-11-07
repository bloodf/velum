package dev.velum.ai.local.asr

import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in physical-device probe. Requires a separately provisioned readable LiteRT modelPath.
 * A pass observes terminal callback entry and restart, not native callback quiescence.
 * If generation finishes before cancellation, the test fails rather than claiming cancellation.
 */
@RunWith(AndroidJUnit4::class)
class LiteRtCancellationDeviceTest {
    @Test fun cancelAfterNativeCallbackThenRestart() {
        val path = InstrumentationRegistry.getArguments().getString("modelPath").orEmpty()
        val model = File(path)
        assertTrue("Pass readable installed supported model as -e modelPath <absolute path>", path.isNotBlank() && model.isFile && model.canRead())
        val done = CountDownLatch(1)
        val first = CountDownLatch(1)
        val terminal = CountDownLatch(1)
        val failed = AtomicReference<Throwable?>()
        val nativeError = AtomicReference<Throwable?>()
        val cancelled = AtomicBoolean(false)
        val startMs = SystemClock.elapsedRealtime()
        // This thread owns the engine and conversation. If native code hangs, retain both.
        val worker = Thread {
            try {
                runBlocking {
                    val engine = Engine(EngineConfig(modelPath = model.absolutePath, backend = Backend.CPU()))
                    try {
                        engine.initialize()
                        val conversation = engine.createConversation(ConversationConfig(systemInstruction = Contents.of("Answer carefully."), maxOutputToken = 512))
                        try {
                            val job = launch(Dispatchers.IO) {
                                awaitLocalText(
                                    start = { onText, generationDone, generationError ->
                                        conversation.sendMessageAsync("Explain the history of mathematics in detail.", object : MessageCallback {
                                            override fun onMessage(message: Message) {
                                                message.contents.contents.filterIsInstance<Content.Text>().forEach { onText(it.text) }
                                                first.countDown()
                                            }
                                            override fun onDone() {
                                                terminal.countDown()
                                                generationDone()
                                            }
                                            override fun onError(throwable: Throwable) {
                                                nativeError.set(throwable)
                                                terminal.countDown()
                                                generationError(throwable)
                                            }
                                        })
                                    },
                                    cancel = { cancelled.set(true); conversation.cancelProcess() },
                                )
                            }
                            if (!first.await(120, TimeUnit.SECONDS)) {
                                job.cancel()
                                job.join() // Wait for terminal callback entry before releasing handles.
                                error("No first native callback within 120 seconds")
                            }
                            Log.i("VelumCancelTest", "firstCallbackMs=${SystemClock.elapsedRealtime() - startMs}")
                            val cancelMs = SystemClock.elapsedRealtime()
                            job.cancel()
                            // Terminal entry does not prove callback return or native quiescence.
                            job.join()
                            check(cancelled.get()) { "Generation completed before cancellation was delivered" }
                            check(terminal.count == 0L) { "Job exited without terminal callback" }
                            nativeError.get()?.let { throw AssertionError("Native cancellation failed", it) }
                            Log.i("VelumCancelTest", "cancelToTerminalMs=${SystemClock.elapsedRealtime() - cancelMs}")
                        } finally { conversation.close() }
                        val restartMs = SystemClock.elapsedRealtime()
                        val restart = engine.createConversation(ConversationConfig(systemInstruction = Contents.of("Answer briefly."), maxOutputToken = 32))
                        try {
                            val text = awaitLocalText(
                                start = { onText, generationDone, generationError ->
                                    restart.sendMessageAsync("Say hello.", object : MessageCallback {
                                        override fun onMessage(message: Message) { message.contents.contents.filterIsInstance<Content.Text>().forEach { onText(it.text) } }
                                        override fun onDone() = generationDone()
                                        override fun onError(throwable: Throwable) = generationError(throwable)
                                    })
                                }, cancel = restart::cancelProcess,
                            )
                            check(text.isNotBlank()) { "Restart produced no text" }
                            Log.i("VelumCancelTest", "restartTerminalMs=${SystemClock.elapsedRealtime() - restartMs}")
                        } finally { restart.close() }
                    } finally { if (engine.isInitialized()) engine.close() }
                }
            } catch (t: Throwable) { failed.set(t) } finally { done.countDown() }
        }
        worker.isDaemon = true
        worker.start()
        assertTrue("Worker did not finish in 300 seconds; resources retained until callback/process exit", done.await(300, TimeUnit.SECONDS))
        failed.get()?.let { throw AssertionError("Native cancellation/restart failed", it) }
    }
}
