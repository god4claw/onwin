package com.example.onwin.data.protocol

import android.content.Context
import android.net.Uri
import com.example.speech.api.ProtocolNotes
import com.example.speech.api.TranscriptLanguage
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Сохраняет протокол в выбранный документ. */
class ProtocolExporter @Inject constructor(@ApplicationContext context: Context) {
    private val resolver = context.contentResolver

    internal suspend fun write(destination: Uri, markdown: String) = withContext(Dispatchers.IO) {
        val output = resolver.openOutputStream(destination)
            ?: throw IOException("Could not open protocol destination")
        output.bufferedWriter(Charsets.UTF_8).use { it.write(markdown) }
    }
}

/** Markdown-представление протокола для экрана и экспорта. */
object ProtocolMarkdown {

    fun render(
        protocol: ProtocolNotes,
        title: String,
        modelLabel: String = "Qwen2.5 0.5B",
    ): String = buildString {
        appendLine("# Протокол заседания")
        appendLine()
        appendLine("**Запись:** ${title.ifBlank { "без названия" }}")
        appendLine("**Модель:** $modelLabel")
        if (protocol.language == TranscriptLanguage.EN) {
            appendLine("**Язык:** английский · черновик (ASR может ошибаться)")
        }
        appendLine()

        appendLine("## Участники")
        if (protocol.participants.isEmpty()) {
            appendLine("- не определены")
        } else {
            protocol.participants.forEach { participant ->
                appendLine("- ${participant.name.ifBlank { "имя не указано" }} — ${participant.speakerId}")
            }
        }
        appendLine()

        appendLine("## Краткое содержание")
        appendLine(protocol.summary.ifBlank { "Модель не сформулировала краткое содержание." })
        appendLine()

        appendLine("## Решения")
        if (protocol.decisions.isEmpty()) {
            appendLine("Решения не зафиксированы.")
        } else {
            protocol.decisions.forEachIndexed { index, decision -> appendLine("${index + 1}. $decision") }
        }
        appendLine()

        appendLine("## Поручения")
        if (protocol.actions.isEmpty()) {
            appendLine("Поручения не зафиксированы.")
        } else {
            protocol.actions.forEachIndexed { index, action ->
                append(index + 1)
                append(". ")
                append(action.task)
                if (action.owner.isNotBlank()) append(" — ${action.owner}")
                if (action.due.isNotBlank()) append(", срок: ${action.due}")
                appendLine()
            }
        }
    }.trimEnd() + "\n"
}
