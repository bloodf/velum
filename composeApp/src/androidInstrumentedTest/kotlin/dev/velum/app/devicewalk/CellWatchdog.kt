package dev.velum.app.devicewalk

import android.os.Process
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Fails a walk cell that stops making progress instead of letting it run to the CI job limit.
 *
 * Not org.junit.rules.Timeout: that runs the whole inner statement (ActivityScenario launch, the compose
 * test environment, Espresso) on a second thread, and on expiry throws without waiting for it, so teardown
 * would race the next cell. This rule keeps the test on the instrumentation thread and watches from a
 * daemon thread. On expiry it logs every thread's stack (and saves it next to the screenshots), interrupts
 * the test thread (Espresso.onIdle's FutureTask.get is interruptible), and kills the process if the thread
 * is still stuck after [KILL_GRACE_MS] so the run still ends with a crash report instead of hanging.
 * Place it as the OUTER rule so it also covers the compose rule's own startup and teardown.
 */
class CellWatchdog(private val stackDir: () -> File) : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val limitMs = InstrumentationRegistry.getArguments().getString(TIMEOUT_ARG)
                ?.toLongOrNull()?.times(1000) ?: DEFAULT_LIMIT_MS
            val testThread = Thread.currentThread()
            val fired = AtomicBoolean(false)
            val watcher = Thread {
                try {
                    Thread.sleep(limitMs)
                    fired.set(true)
                    val dump = Thread.getAllStackTraces().entries.joinToString("\n\n") { (t, frames) ->
                        "\"${t.name}\" ${t.state}\n" + frames.joinToString("\n") { "    at $it" }
                    }
                    Log.e(TAG, "${description.methodName} exceeded ${limitMs / 1000}s; stacks:\n$dump")
                    runCatching { File(stackDir(), "watchdog-${description.methodName}.txt").writeText(dump) }
                    testThread.interrupt()
                    Thread.sleep(KILL_GRACE_MS)
                    Log.e(TAG, "${description.methodName} still stuck after interrupt; killing process")
                    Process.killProcess(Process.myPid())
                } catch (_: InterruptedException) {
                    // cell finished in time
                }
            }.apply { name = "walk-cell-watchdog"; isDaemon = true; start() }
            try {
                base.evaluate()
            } catch (t: Throwable) {
                if (fired.get()) {
                    throw AssertionError("Walk cell ${description.methodName} hung: no result in ${limitMs / 1000}s (stacks in logcat tag $TAG)", t)
                }
                throw t
            } finally {
                watcher.interrupt()
                Thread.interrupted() // clear a late interrupt so teardown of later cells is unaffected
            }
            if (fired.get()) throw AssertionError("Walk cell ${description.methodName} exceeded ${limitMs / 1000}s")
        }
    }

    private companion object {
        const val TAG = "WalkWatchdog"
        const val TIMEOUT_ARG = "velumWalkCellTimeoutSec"
        const val DEFAULT_LIMIT_MS = 25 * 60 * 1000L
        const val KILL_GRACE_MS = 20_000L
    }
}
