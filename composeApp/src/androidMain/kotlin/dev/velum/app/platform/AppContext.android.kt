package dev.velum.app.platform

import android.content.Context

private var appContext: Context? = null

fun installAppContext(context: Context) {
    appContext = context.applicationContext
}

fun requireAppContext(): Context =
    appContext ?: error("App context not installed")

fun appContextOrNull(): Context? = appContext
