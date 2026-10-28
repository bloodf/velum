package dev.velum.app.platform

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import dev.velum.app.export.PdfTextLayout
import dev.velum.app.export.shareBytes
import java.io.ByteArrayOutputStream

actual fun canExportPdf(): Boolean = true

actual fun sharePdf(title: String, bodyPlain: String) {
    val safeTitle = title.trim().ifBlank { "Velum" }
    val body = bodyPlain.ifBlank { " " }
    val pageWidth = 595
    val pageHeight = 842
    val margin = 48f
    val titleSize = 16f
    val bodySize = 11f
    val lineHeight = 16f
    val usableHeight = pageHeight - margin * 2 - titleSize - 24f
    val linesPerPage = (usableHeight / lineHeight).toInt().coerceAtLeast(1)
    // ~90 chars at 11pt in ~500pt width for monospace-ish average.
    val maxChars = 90
    val wrapped = PdfTextLayout.wrapBody(body, maxChars)
    val pages = PdfTextLayout.paginate(wrapped, linesPerPage)

    val document = PdfDocument()
    val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = titleSize
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        color = 0xFF111111.toInt()
    }
    val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = bodySize
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        color = 0xFF222222.toInt()
    }

    pages.forEachIndexed { index, pageLines ->
        val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, index + 1).create()
        val page = document.startPage(pageInfo)
        val canvas: Canvas = page.canvas
        var y = margin + titleSize
        if (index == 0) {
            canvas.drawText(safeTitle.take(80), margin, y, titlePaint)
            y += titleSize + 18f
        } else {
            y = margin + bodySize
        }
        pageLines.forEach { line ->
            canvas.drawText(line, margin, y, bodyPaint)
            y += lineHeight
        }
        document.finishPage(page)
    }

    val bytes = ByteArrayOutputStream().use { out ->
        document.writeTo(out)
        document.close()
        out.toByteArray()
    }
    val fileName = safeTitle
        .replace(Regex("[^a-zA-Z0-9._-]"), "_")
        .ifBlank { "velum" } + ".pdf"
    shareBytes(fileName, "application/pdf", bytes)
}
