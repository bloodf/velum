package dev.velum.core.templates

/**
 * iOS ships without the full Plaud gzip asset until the app bundle copies it.
 * Android-first: JVM/Android load and gunzip from classpath resources.
 */
internal actual object PlaudFullTemplateAsset {
    actual fun readJsonl(): String = ""
}
