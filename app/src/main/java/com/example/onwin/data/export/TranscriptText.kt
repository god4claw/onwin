package com.example.onwin.data.export

import com.example.onwin.domain.model.SessionDetail
import java.util.Locale

internal data class ExportTranscript(val title: String, val utterances: List<ExportUtterance>)

internal data class ExportUtterance(val interval: String, val speaker: String, val text: String) {
    val heading: String get() = "[$interval] $speaker"
}

internal fun SessionDetail.toExportTranscript(defaultSpeaker: (Int) -> String): ExportTranscript {
    val names = speakerNames.associate { it.speaker to it.name }
    return ExportTranscript(session.title, segments.sortedBy { it.index }.map { segment ->
        ExportUtterance(
            interval = "${timestamp(segment.startMs)} – ${timestamp(segment.endMs)}",
            speaker = names[segment.speaker] ?: defaultSpeaker(segment.speaker),
            text = segment.text,
        )
    })
}

internal fun ExportTranscript.toPlainText(): String = buildString {
    appendLine(title)
    for (utterance in utterances) {
        appendLine()
        appendLine(utterance.heading)
        appendLine(utterance.text)
    }
}

internal fun ExportTranscript.toMarkdown(): String = buildString {
    appendLine("# ${title.markdownInline()}")
    for (utterance in utterances) {
        appendLine()
        appendLine("**${utterance.heading.markdownInline()}**")
        appendLine()
        for (line in utterance.text.lineSequence()) {
            val indentation = line.takeWhile { it == ' ' || it == '\t' }
            append(indentation.replace(" ", "&#32;").replace("\t", "&#9;"))
            appendLine(line.drop(indentation.length).markdownInline().trimEnd())
        }
    }
}

private val markdownPunctuation = Regex("""[\x21-\x2f\x3a-\x40\x5b-\x60\x7b-\x7e]""")

private fun String.markdownInline(): String =
    replace('\n', ' ').replace('\r', ' ').replace(markdownPunctuation) { "\\${it.value}" }

private fun timestamp(ms: Long): String = String.format(
    Locale.ROOT,
    "%02d:%02d:%02d.%03d",
    ms / 3_600_000,
    ms / 60_000 % 60,
    ms / 1_000 % 60,
    ms % 1_000,
)