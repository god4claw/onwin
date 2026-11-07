package com.example.onwin.data.export

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TranscriptPdfTest {
    @Test
    fun longCyrillicUtteranceWrapsAndContinuesOnTheNextPage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("transcript-", ".pdf", context.cacheDir)
        try {
            val transcript = ExportTranscript(
                "Расшифровка встречи",
                listOf(
                    ExportUtterance(
                        "00:00:00.000 – 00:10:00.000",
                        "Анна",
                        "Длинная реплика с кириллицей и пробелами. ".repeat(250),
                    ),
                ),
            )
            file.outputStream().use { writeTranscriptPdf(transcript, it) }
            val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            PdfRenderer(descriptor).use { renderer ->
                assertTrue(renderer.pageCount > 1)
                for (index in 0 until renderer.pageCount) {
                    renderer.openPage(index).use { page ->
                        val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                        try {
                            bitmap.eraseColor(Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            val rowsWithText = (48 until page.height - 48).count { y ->
                                (48 until page.width - 48).any { x -> Color.red(bitmap.getPixel(x, y)) < 128 }
                            }
                            assertTrue("Page ${index + 1} should contain wrapped text", rowsWithText > 12)
                        } finally {
                            bitmap.recycle()
                        }
                    }
                }
            }
        } finally {
            file.delete()
        }
    }
}