package dev.velum.core.templates

/**
 * iOS ships without the Plaud JSONL asset until the app bundle copies it.
 * Android-first: JVM/Android load from classpath resources.
 */
internal actual object PlaudTemplateAsset {
    actual fun readJsonl(): String = ""
}
