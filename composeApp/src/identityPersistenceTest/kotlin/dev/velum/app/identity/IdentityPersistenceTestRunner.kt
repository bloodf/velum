package dev.velum.app.identity

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

/** Test-only process: never starts the production AppContainer or its recovery jobs. */
class IdentityPersistenceTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application =
        super.newApplication(cl, Application::class.java.name, context)
}
