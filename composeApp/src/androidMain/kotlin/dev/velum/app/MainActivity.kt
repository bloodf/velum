package dev.velum.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.velum.ai.local.ModelStoreDir
import dev.velum.app.di.AppContainer
import dev.velum.core.data.DriverFactory
import dev.velum.core.security.AndroidSecureStore
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val modelsDir = File(applicationContext.filesDir, "models")
        val container = AppContainer(
            secureStore = AndroidSecureStore(applicationContext),
            driverFactory = DriverFactory(applicationContext),
            modelStoreRoot = ModelStoreDir(modelsDir.absolutePath),
        )
        setContent {
            App(container)
        }
    }
}
