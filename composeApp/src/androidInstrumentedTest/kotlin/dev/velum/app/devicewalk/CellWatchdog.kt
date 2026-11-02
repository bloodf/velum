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
 * daemon thread. On expiry it writes every thread's stack to logcat (chunked under the ~4 KB entry limit)
 * and to additionalTestOutputDir (AGP pulls it before the uninstall that removes [stackDir], #160),
 * interrupts the test thread (Espresso.onIdle's FutureTask.get is interruptible), and kills the process
 * if the thread is still stuck after [KILL_GRACE_MS] so the run still ends with a crash report.
 * Place it as the OUTER rule so it also covers the compose rule's own startup and teardown.
 *
 * Only java.* and android.* calls on the watcher path: minifiedTest runs R8 and a stripped stdlib helper
 * must not stop the interrupt and kill from running.
 */
class CellWatchdog(private val stackDir: () -> File) : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val name = description.methodName ?: "cell"
            val args = InstrumentationRegistry.getArguments()
            val limitMs = limitMs(args.getString(TIMEOUT_ARG))
            val testThread = Thread.currentThread()
            val fired = AtomicBoolean(false)
            val watcher = Thread {
                try {
                    Thread.sleep(limitMs)
                    fired.set(true)
                    try {
                        report(name, limitMs, args.getString(OUTPUT_DIR_ARG))
                    } catch (t: Throwable) {
                        Log.e(TAG, "$name stack report failed", t)
                    }
                    testThread.interrupt()
                    Thread.sleep(KILL_GRACE_MS)
                    Log.e(TAG, "$name still stuck after interrupt; killing process")
                    Process.killProcess(Process.myPid())
                } catch (_: InterruptedException) {
                    // cell finished in time
                }
            }
            watcher.name = "walk-cell-watchdog"
            watcher.isDaemon = true
            watcher.start()
            var failure: Throwable? = null
            try {
                base.evaluate()
            } catch (t: Throwable) {
                failure = t
            }
            watcher.interrupt()
            var joined = false
            while (!joined) {
                try { watcher.join(); joined = true } catch (_: InterruptedException) { /* keep waiting; cleared below */ }
            }
            Thread.interrupted() // a late interrupt must not land in the next cell
            if (fired.get()) {
                throw AssertionError("Walk cell $name hung: no result in ${limitMs / 1000}s (stacks in logcat tag $TAG)", failure)
            }
            if (failure != null) throw failure
        }
    }

    private fun limitMs(arg: String?): Long {
        val seconds = try { java.lang.Long.parseLong(arg ?: "") } catch (_: NumberFormatException) { 0L }
        return if (seconds > 0) seconds * 1000 else DEFAULT_LIMIT_MS
    }

    private fun report(name: String, limitMs: Long, outputDir: String?) {
        val sb = StringBuilder()
        for (entry in Thread.getAllStackTraces().entries) {
            sb.append('"').append(entry.key.name).append("\" ").append(entry.key.state).append('\n')
            for (frame in entry.value) sb.append("    at ").append(frame).append('\n')
            sb.append('\n')
        }
        val dump = sb.toString()
        Log.e(TAG, "$name exceeded ${limitMs / 1000}s; thread stacks follow")
        var i = 0
        var n = 0
        while (i < dump.length) {
            val end = Math.min(i + CHUNK, dump.length)
            Log.e(TAG, "$name stacks[$n] ${dump.substring(i, end)}")
            i = end
            n++
        }
        try { File(stackDir(), "watchdog-$name.txt").writeText(dump) } catch (_: Throwable) { /* shotDir may be unset */ }
        if (outputDir != null) {
            try {
                val dir = File(outputDir)
                dir.mkdirs()
                File(dir, "watchdog-$name.txt").writeText(dump)
            } catch (t: Throwable) {
                Log.e(TAG, "$name additional output failed", t)
            }
        }
    }

    private companion object {
        const val TAG = "WalkWatchdog"
        const val TIMEOUT_ARG = "velumWalkCellTimeoutSec"
        const val OUTPUT_DIR_ARG = "additionalTestOutputDir"
        const val DEFAULT_LIMIT_MS = 25 * 60 * 1000L
        const val KILL_GRACE_MS = 20_000L
        const val CHUNK = 1000 // under logcat's ~4 KB entry limit even for 4-byte UTF-8
    }
}
