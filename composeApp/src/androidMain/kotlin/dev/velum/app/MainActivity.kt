package dev.velum.app

import android.content.Intent
import android.os.Bundle
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
            val container = app.awaitContainer()
            setContent {
                App(container)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        IncomingShare.offerFromIntent(this, intent)
    }
}
