package dev.velum.app.platform

/** True when this platform can produce a real PDF share (Android PdfDocument). */
expect fun canExportPdf(): Boolean

/**
 * Build a simple multi-page PDF from [bodyPlain] and open the share sheet / save path.
 * No-op when [canExportPdf] is false.
 */
expect fun sharePdf(title: String, bodyPlain: String)
