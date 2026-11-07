package com.example.onwin.data.export

import android.content.Context
import android.net.Uri
import com.example.onwin.R
import com.example.onwin.domain.model.SessionDetail
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal enum class TranscriptExportFormat(val extension: String, val mimeType: String) {
    JSON("json", "application/json"),
    PDF("pdf", "application/pdf"),
    MARKDOWN("md", "text/markdown"),
    TXT("txt", "text/plain"),
}

class TranscriptExporter @Inject constructor(@ApplicationContext private val context: Context) {
    internal suspend fun write(destination: Uri, format: TranscriptExportFormat, detail: SessionDetail) =
        withContext(Dispatchers.IO) {
            val output = context.contentResolver.openOutputStream(destination)
                ?: throw IOException("Could not open export destination")
            output.buffered().use { stream ->
                when (format) {
                    TranscriptExportFormat.JSON ->
                        stream.write(encodeTranscriptJson(detail.segments).toByteArray(Charsets.UTF_8))
                    TranscriptExportFormat.PDF -> writeTranscriptPdf(transcript(detail), stream)
                    TranscriptExportFormat.MARKDOWN ->
                        stream.write(transcript(detail).toMarkdown().toByteArray(Charsets.UTF_8))
                    TranscriptExportFormat.TXT ->
                        stream.write(transcript(detail).toPlainText().toByteArray(Charsets.UTF_8))
                }
            }
        }

    private fun transcript(detail: SessionDetail): ExportTranscript =
        detail.toExportTranscript { speaker -> context.getString(R.string.speaker_default, speaker + 1) }
}