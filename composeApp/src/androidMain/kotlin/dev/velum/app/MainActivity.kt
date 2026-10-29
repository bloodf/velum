package dev.velum.app

import android.content.Intent
import android.os.Bundle
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
import androidx.lifecycle.lifecycleScope
import dev.velum.app.di.AppContainer
import dev.velum.app.platform.IncomingShare
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        val app = application as VelumApplication
        splash.setKeepOnScreenCondition { !app.isContainerReady() }
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        IncomingShare.offerFromIntent(this, intent)
        // Install a Compose hierarchy immediately so AndroidComposeTestRule can
        // synchronize under R8 minifiedTest. Await the container on
        // lifecycleScope (not LaunchedEffect) — the effect path left minified
        // walks on an empty Box for 90s while debug still composed (#106).
        var container by mutableStateOf<AppContainer?>(null)
        var fatal by mutableStateOf(false)
        setContent {
            val ready = container
            when {
                ready != null -> App(ready)
                fatal -> {
                    Text(
                        text = FATAL_COPY,
                        fontSize = 16.sp,
                        modifier = Modifier.padding(48.dp),
                    )
                }
                else -> Box(Modifier.fillMaxSize())
            }
        }
        lifecycleScope.launch {
            try {
                container = app.awaitContainer()
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                fatal = true
                Log.e(TAG, "Fatal AppContainer init", t)
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
        private const val FATAL_COPY =
            "Velum failed to start. Reinstall or clear app storage.\n" +
                "O Velum não iniciou. Reinstale ou limpe o armazenamento do app."
    }
}
