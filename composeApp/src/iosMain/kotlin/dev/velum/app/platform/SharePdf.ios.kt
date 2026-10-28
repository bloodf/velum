package dev.velum.app.platform

actual fun canExportPdf(): Boolean = false

actual fun sharePdf(title: String, bodyPlain: String) = Unit
