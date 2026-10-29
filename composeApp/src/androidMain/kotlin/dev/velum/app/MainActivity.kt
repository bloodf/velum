package dev.velum.app

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import dev.velum.app.di.AppContainer
import dev.velum.app.platform.IncomingShare
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

class MainActivity : ComponentActivity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val handoffStarted = AtomicBoolean(false)
    private val composeReady = AtomicBoolean(false)
    private val fatalUi = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        val app = application as VelumApplication
        // Keep splash until Compose actually hosts App / fatal — not merely until
        // the process-scoped container flag flips (that can dismiss splash onto an
        // empty Box if the handoff post has not replaced setContent yet) (#106).
        splash.setKeepOnScreenCondition { !composeReady.get() && !fatalUi.get() }
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        IncomingShare.offerFromIntent(this, intent)
        // Compose hierarchy immediately for AndroidComposeTestRule (#106).
        // When the process-scoped container is already ready (later instrumented
        // cells), seed App on the first frame — do not wait on a Main coroutine
        // that Compose idle polling can starve under R8 minifiedTest.
        val seeded =
            if (app.isContainerReady()) {
                runCatching { VelumApplication.container }.getOrNull()
            } else {
                null
            }
        if (seeded != null) {
            showApp(seeded)
        } else {
            setContent {
                Box(Modifier.fillMaxSize())
            }
            startContainerHandoff(app)
        }
    }

    private fun showApp(container: AppContainer) {
        composeReady.set(true)
        setContent { App(container) }
        Log.i(TAG, "Compose App set")
    }

    private fun showFatal(t: Throwable) {
        fatalUi.set(true)
        Log.e(TAG, "Fatal AppContainer init", t)
        setContent {
            Text(
                text = FATAL_COPY,
                fontSize = 16.sp,
                modifier = Modifier.padding(48.dp),
            )
        }
    }

    /**
     * Await the process-scoped container off the main looper, then replace
     * setContent on Main. lifecycleScope + Dispatchers.Main can sit unresumed
     * under Compose UI Test idle polling on R8 minifiedTest; a Handler post +
     * full setContent replace avoids leaving an empty Box for waitForShell (#106).
     *
     * CancellationException is not swallowed into fatal — the handoff executor is not
     * a structured coroutine Job, so a cancelled Deferred surfaces as failure only when
     * init itself failed (see [VelumApplication.containerInitError]).
     */
    private fun startContainerHandoff(app: VelumApplication) {
        if (!handoffStarted.compareAndSet(false, true)) return
        HANDOFF_EXECUTOR.execute {
            val result =
                try {
                    Result.success(runBlocking { app.awaitContainer() })
                } catch (_: CancellationException) {
                    // Must not become fatal — never map cancel into the fatal UI (#106 CTO).
                    Log.w(TAG, "Container handoff cancelled; not marking fatal")
                    return@execute
                } catch (t: Throwable) {
                    val err = app.containerInitError()
                    if (app.isContainerReady() && err == null) {
                        runCatching { VelumApplication.container }
                            .fold(
                                onSuccess = { Result.success(it) },
                                onFailure = { Result.failure(err ?: t) },
                            )
                    } else {
                        Result.failure(err ?: t)
                    }
                }
            mainHandler.post {
                if (isDestroyed) return@post
                result.fold(
                    onSuccess = { showApp(it) },
                    onFailure = { showFatal(it) },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        IncomingShare.offerFromIntent(this, intent)
    }

    companion object {
        private const val TAG = "VelumMain"
        private val HANDOFF_EXECUTOR = Executors.newSingleThreadExecutor { r ->
            Thread(r, "velum-container-handoff").apply { isDaemon = true }
        }
        private const val FATAL_COPY =
            "Velum failed to start. Reinstall or clear app storage.\n" +
                "O Velum não iniciou. Reinstale ou limpe o armazenamento do app."
    }
}
