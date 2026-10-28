package dev.velum.app

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import dev.velum.app.platform.IncomingShare
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        val app = application as VelumApplication
        splash.setKeepOnScreenCondition { !app.isContainerReady() }
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        IncomingShare.offerFromIntent(this, intent)
        lifecycleScope.launch {
            try {
                val container = app.awaitContainer()
                setContent {
                    App(container)
                }
            } catch (t: Throwable) {
                // Plain EN/pt-BR copy — never surface raw exception text.
                val detail =
                    "Velum failed to start. Reinstall or clear app storage.\n" +
                        "O Velum não iniciou. Reinstale ou limpe o armazenamento do app."
                setContentView(
                    TextView(this@MainActivity).apply {
                        text = detail
                        textSize = 16f
                        setPadding(48, 96, 48, 48)
                        contentDescription = detail
                    },
                )
                android.util.Log.e("VelumMain", "Fatal AppContainer init", t)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        IncomingShare.offerFromIntent(this, intent)
    }
}
