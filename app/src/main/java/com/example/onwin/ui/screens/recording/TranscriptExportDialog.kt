package com.example.onwin.ui.screens.recording

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.onwin.R
import com.example.onwin.data.export.TranscriptExportFormat
import com.example.onwin.ui.components.GlassButton
import com.example.onwin.ui.components.OnWinDialog

@Composable
internal fun TranscriptExportDialog(
    onDismiss: () -> Unit,
    onFormatSelected: (TranscriptExportFormat) -> Unit,
) {
    OnWinDialog(
        title = stringResource(R.string.recording_export_format_title),
        onDismiss = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (row in TranscriptExportFormat.entries.chunked(2)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (format in row) {
                        val label = when (format) {
                            TranscriptExportFormat.JSON -> "JSON"
                            TranscriptExportFormat.PDF -> "PDF"
                            TranscriptExportFormat.MARKDOWN -> "Markdown"
                            TranscriptExportFormat.TXT -> "TXT"
                        }
                        GlassButton(
                            text = label,
                            onClick = { onFormatSelected(format) },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

internal data class TranscriptDocumentRequest(val format: TranscriptExportFormat, val title: String)

internal object TranscriptDocumentContract : ActivityResultContract<TranscriptDocumentRequest, Uri?>() {
    override fun createIntent(context: Context, input: TranscriptDocumentRequest): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(input.format.mimeType)
            .putExtra(Intent.EXTRA_TITLE, transcriptFileName(input.title, input.format))

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == Activity.RESULT_OK) intent?.data else null
}

private val invalidFileNameCharacters = Regex("""[\\/:*?"<>|\p{Cntrl}]""")

private fun transcriptFileName(title: String, format: TranscriptExportFormat): String {
    val name = title.trim().replace(invalidFileNameCharacters, "_").trimEnd('.', ' ')
    return "${name.ifEmpty { "transcript" }}.${format.extension}"
}