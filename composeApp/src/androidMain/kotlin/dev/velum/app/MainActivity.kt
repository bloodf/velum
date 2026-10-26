package dev.velum.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.velum.app.di.AppContainer
import dev.velum.core.security.AndroidSecureStore

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = AppContainer(secureStore = AndroidSecureStore(applicationContext))
        setContent {
            App(container)
        }
    }
}
