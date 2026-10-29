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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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

    /** Single setContent host — swapping setContent left an empty ComposeView a11y tree (#106). */
    private var hostedContainer by mutableStateOf<AppContainer?>(null)
    private var hostedFatal by mutableStateOf<Throwable?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        val app = application as VelumApplication
        // Keep splash until Compose actually hosts App / fatal — not merely until
        // the process-scoped container flag flips (#106).
        splash.setKeepOnScreenCondition { !composeReady.get() && !fatalUi.get() }
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        IncomingShare.offerFromIntent(this, intent)
        // One setContent for the activity lifetime. Seed App when the
        // process-scoped container is already ready (later instrumented cells);
        // otherwise show an empty host and hand off off-main (#106).
        val seeded =
            if (app.isContainerReady()) {
                runCatching { VelumApplication.container }.getOrNull()
            } else {
                null
            }
        if (seeded != null) {
            hostedContainer = seeded
            composeReady.set(true)
        } else {
            startContainerHandoff(app)
        }
        setContent {
            val fatal = hostedFatal
            val container = hostedContainer
            when {
                fatal != null -> {
                    Text(
                        text = FATAL_COPY,
                        fontSize = 16.sp,
                        modifier = Modifier.padding(48.dp),
                    )
                }
                container != null -> App(container)
                else -> Box(Modifier.fillMaxSize())
            }
        }
        if (seeded != null) {
            Log.i(TAG, "Compose App set")
        }
    }

    private fun showApp(container: AppContainer) {
        hostedFatal = null
        hostedContainer = container
        composeReady.set(true)
        Log.i(TAG, "Compose App set")
    }

    private fun showFatal(t: Throwable) {
        fatalUi.set(true)
        Log.e(TAG, "Fatal AppContainer init", t)
        hostedContainer = null
        hostedFatal = t
    }

    /**
     * Await the process-scoped container off the main looper, then update the
     * single setContent host on Main. lifecycleScope + Dispatchers.Main can sit
     * unresumed under Compose UI Test idle polling on R8 minifiedTest (#106).
     *
     * [CancellationException]: if the activity is already destroyed, ignore (do
     * not show fatal). If still alive, startup itself was cancelled — show fatal
     * so splash always ends (28fcef0 / #106). Never leave both ready and fatal
     * false while the activity is on screen.
     */
    private fun startContainerHandoff(app: VelumApplication) {
        if (!handoffStarted.compareAndSet(false, true)) return
        HANDOFF_EXECUTOR.execute {
            val result =
                try {
                    Result.success(runBlocking { app.awaitContainer() })
                } catch (ce: CancellationException) {
                    Log.w(TAG, "Container handoff cancelled", ce)
                    mainHandler.post {
                        when (dispositionForHandoffCancellation(isDestroyed)) {
                            HandoffCancelDisposition.Ignore -> Unit
                            HandoffCancelDisposition.ShowFatal -> showFatal(ce)
                        }
                    }
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
