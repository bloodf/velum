package dev.velum.core.autoflow

/**
 * iOS ships without the AutoFlow JSONL asset until the app bundle copies it.
 * Android-first: JVM/Android load from classpath resources.
 */
internal actual object AutoFlowTemplateAsset {
    actual fun readJsonl(): String = ""
}
