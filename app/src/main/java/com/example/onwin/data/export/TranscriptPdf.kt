package com.example.onwin.data.export

import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.StaticLayout
import android.text.TextPaint
import java.io.OutputStream

private const val PAGE_WIDTH = 595
private const val PAGE_HEIGHT = 842
private const val MARGIN = 48

internal fun writeTranscriptPdf(transcript: ExportTranscript, output: OutputStream) {
    val document = PdfDocument()
    try {
        val layout = TranscriptPdfLayout(document)
        try {
            layout.paragraph(transcript.title, 20f, bold = true)
            for (utterance in transcript.utterances) {
                layout.paragraph(utterance.heading, 11f, bold = true, spacingBefore = 12)
                layout.paragraph(utterance.text, 11f)
            }
        } finally {
            layout.finish()
        }
        document.writeTo(output)
    } finally {
        document.close()
    }
}

private class TranscriptPdfLayout(private val document: PdfDocument) {
    private var page: PdfDocument.Page? = null
    private var pageNumber = 0
    private var y = MARGIN

    fun paragraph(text: String, size: Float, bold: Boolean = false, spacingBefore: Int = 0) {
        val paint = TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, PAGE_WIDTH - 2 * MARGIN).build()

        if (y > MARGIN) y += spacingBefore
        var startLine = 0
        while (startLine < layout.lineCount) {
            val top = layout.getLineTop(startLine)
            if (page == null || y + layout.getLineBottom(startLine) - top > PAGE_HEIGHT - MARGIN) {
                finish()
                page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, ++pageNumber).create())
                y = MARGIN
            }
            var endLine = startLine + 1
            while (endLine < layout.lineCount && y + layout.getLineBottom(endLine) - top <= PAGE_HEIGHT - MARGIN) {
                endLine++
            }
            val height = layout.getLineBottom(endLine - 1) - top
            val canvas = checkNotNull(page).canvas
            val saved = canvas.save()
            try {
                canvas.clipRect(MARGIN, y, PAGE_WIDTH - MARGIN, y + height)
                canvas.translate(MARGIN.toFloat(), (y - top).toFloat())
                layout.draw(canvas)
            } finally {
                canvas.restoreToCount(saved)
            }
            y += height
            startLine = endLine
        }
    }

    fun finish() {
        page?.let(document::finishPage)
        page = null
    }
}