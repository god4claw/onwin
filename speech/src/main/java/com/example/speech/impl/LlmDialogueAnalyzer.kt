package com.example.speech.impl

import com.example.speech.api.DialogueNotes
import com.example.speech.api.Segment
import com.example.speech.api.TextGenerator
import com.example.speech.api.TranscriptAnalyzer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [TranscriptAnalyzer] на локальной LLM: имена спикеров и краткое резюме
 * по всему диалогу.
 *
 * Устройство и почему так:
 * - диалог режется на куски по [maxCharsPerChunk], имена собираются по всем
 *   кускам: имя, названное один раз в середине разговора, иначе не найти;
 * - имя принимается, только если оно не спорит с другими кусками (при равенстве
 *   голосов — отказ): выдуманное имя хуже, чем никакого;
 * - резюме — один запрос по сокращённой расшифровке, с валидацией: пустое,
 *   слишком короткое, похожее на эхо промпта или без кириллицы отбрасывается;
 * - бюджет времени на каждый запрос: зависшая модель не держит очередь;
 * - любая ошибка генератора — это `null`, а не исключение: расшифровка важнее.
 *
 * @param maxCharsPerChunk бюджет символов на один кусок диалога при сборе имён.
 * @param maxCharsForSummary бюджет символов для запроса резюме (начало и конец).
 * @param maxOutputTokens потолок ответа модели.
 * @param budgetMs бюджет на один запрос.
 */
class LlmDialogueAnalyzer(
    private val generator: TextGenerator,
    private val maxCharsPerChunk: Int = 3_000,
    private val maxCharsForSummary: Int = 6_000,
    private val maxOutputTokens: Int = 320,
    private val budgetMs: Long = 20_000,
) : TranscriptAnalyzer {

    init {
        require(maxCharsPerChunk > 0) { "maxCharsPerChunk должен быть > 0, получено $maxCharsPerChunk" }
        require(maxCharsForSummary > 0) { "maxCharsForSummary должен быть > 0, получено $maxCharsForSummary" }
        require(maxOutputTokens > 0) { "maxOutputTokens должен быть > 0, получено $maxOutputTokens" }
        require(budgetMs > 0) { "budgetMs должен быть > 0, получено $budgetMs" }
    }

    override suspend fun load() = generator.load()

    override fun release() = generator.release()

    override suspend fun analyze(segments: List<Segment>): DialogueNotes? {
        val dialogue = segments.filter { it.text.isNotBlank() }
        if (dialogue.isEmpty()) return null

        val notes = DialogueNotes(
            speakerNames = collectNames(dialogue),
            summary = summarize(dialogue),
        )
        return notes.takeUnless { it.isEmpty }
    }

    /** Имена по всем кускам: побеждает имя с большинством голосов, ничья — отказ. */
    private suspend fun collectNames(segments: List<Segment>): Map<Int, String> {
        val chunks = DialogueChunker.chunk(segments, maxCharsPerChunk)
        val votes = mutableMapOf<Int, MutableMap<String, Int>>()

        for (chunk in chunks) {
            val prompt = buildString {
                appendLine(NAME_RULES)
                appendLine()
                append(DialogueText.forModel(chunk))
            }
            val answer = generate(prompt) ?: continue
            for ((speaker, name) in SpeakerNameParser.parse(answer)) {
                val counter = votes.getOrPut(speaker) { mutableMapOf() }
                counter[name] = (counter[name] ?: 0) + 1
            }
        }

        return votes.mapNotNull { (speaker, names) ->
            val ranked = names.entries.sortedByDescending { it.value }
            val best = ranked.first()
            val runnerUp = ranked.getOrNull(1)
            if (runnerUp != null && runnerUp.value == best.value) null else speaker to best.key
        }.toMap()
    }

    /** Резюме по сокращённой расшифровке; мусор отбрасывается. */
    private suspend fun summarize(segments: List<Segment>): String {
        val transcript = DialogueText.forModel(segments, limit = maxCharsForSummary)
        val prompt = buildString {
            appendLine(SUMMARY_RULES)
            appendLine()
            append(transcript)
        }
        return SummarySanitizer.clean(generate(prompt) ?: "")
    }

    /** Один запрос к модели; ошибка или таймаут — `null`, отмена — пробрасывается. */
    private suspend fun generate(prompt: String): String? = try {
        withTimeoutOrNull(budgetMs) { generator.generate(prompt, maxOutputTokens) }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        null
    }

    internal companion object {
        val NAME_RULES: String = """
            Ты читаешь расшифровку разговора и выписываешь имена участников.
            Для каждого спикера, чьё имя или роль явно прозвучали в тексте, верни строку «номер: имя».
            Пиши только то, что сказано в диалоге: «меня зовут Анна», «это Сергей», «я врач».
            Если имя не названо — не пиши строку для этого спикера.
            Ничего не придумывай, не добавляй пояснений и заголовков.
        """.trimIndent()

        val SUMMARY_RULES: String = """
            Ты делаешь краткое резюме разговора на русском языке.
            Верни 3–5 предложений: о чём говорили, что решили, что осталось сделать.
            Только факты из диалога, без выдумок, без заголовков и без обращения к читателю.
        """.trimIndent()
    }
}

/** Диалог в виде, который читает модель: `Спикер N: текст`. */
internal object DialogueText {

    /**
     * @param limit бюджет символов; при превышении берётся начало и конец
     *   с многоточием — середина длинного разговора для резюме не критична.
     */
    fun forModel(segments: List<Segment>, limit: Int = Int.MAX_VALUE): String {
        val full = buildString {
            segments.forEachIndexed { index, segment ->
                if (index > 0) append('\n')
                append("Спикер ")
                append(segment.speaker + 1)
                append(": ")
                append(segment.text.trim())
            }
        }
        if (full.length <= limit) return full
        val head = full.take(limit / 2).trimEnd()
        val tail = full.takeLast(limit / 2).trimStart()
        return "$head … $tail"
    }
}

/** Режет диалог на куски по бюджету символов, не разрывая реплику. */
internal object DialogueChunker {

    fun chunk(segments: List<Segment>, maxChars: Int): List<List<Segment>> {
        require(maxChars > 0) { "maxChars должен быть > 0, получено $maxChars" }
        val chunks = mutableListOf<MutableList<Segment>>()
        var current = mutableListOf<Segment>()
        var size = 0
        for (segment in segments) {
            val length = segment.text.length + LABEL_OVERHEAD
            if (current.isNotEmpty() && size + length > maxChars) {
                chunks += current
                current = mutableListOf()
                size = 0
            }
            current += segment
            size += length
        }
        if (current.isNotEmpty()) chunks += current
        return chunks
    }

    /** «Спикер 12: » — примерная цена подписи в символах. */
    private const val LABEL_OVERHEAD = 12
}

/**
 * Разбирает ответ модели «номер: имя».
 *
 * Строгий разбор: строка без номера, с цифрами в имени или с заглушкой
 * («Спикер 1», «неизвестно») отбрасывается. Лучше без имени, чем с выдуманным.
 */
internal object SpeakerNameParser {

    private val LINE = Regex("""^\s*[-•*]?\s*(\d{1,2})\s*[:\-–—]\s*(.+?)\s*$""")
    private val NAME = Regex("""^[А-ЯЁA-Z][а-яёa-z]*(?:[ -][А-ЯЁA-Zа-яёa-z]+){0,2}$""")

    private val PLACEHOLDERS = setOf(
        "спикер", "говорящий", "неизвестно", "неизвестный", "нет", "не назван", "не названо",
    )

    fun parse(raw: String): Map<Int, String> = buildMap {
        raw.lineSequence().forEach { line ->
            val match = LINE.find(line) ?: return@forEach
            // В промпте спикеры нумеруются с единицы («Спикер 1»), а в модели — с нуля.
            val number = match.groupValues[1].toIntOrNull() ?: return@forEach
            if (number < 1) return@forEach
            val name = match.groupValues[2].trim(' ', '.', ',', ';', '"', '\'', '«', '»')
            if (isValidName(name)) put(number - 1, name)
        }
    }

    fun isValidName(name: String): Boolean {
        if (name.length !in 2..40) return false
        val lower = name.lowercase()
        if (lower in PLACEHOLDERS || lower.startsWith("спикер") || lower.startsWith("говорящ")) return false
        return NAME.matches(name)
    }
}

/**
 * Проверяет резюме: пустое, короткое, похожее на эхо промпта или без кириллицы
 * отбрасывается. Длинное обрезается по границе предложения.
 */
internal object SummarySanitizer {

    private const val MIN_CHARS = 40
    private const val MAX_CHARS = 800
    private val WHITESPACE = Regex("\\s+")
    private val SPEAKER_ECHO = Regex("""спикер\s*\d+\s*:""", RegexOption.IGNORE_CASE)

    fun clean(raw: String): String {
        val text = normalize(raw)
        if (text.length < MIN_CHARS) return ""
        if (text.contains('|')) return ""
        if (SPEAKER_ECHO.containsMatchIn(text)) return ""
        if (text.count(::isCyrillic) < 10) return ""
        return trimToLimit(text)
    }

    private fun normalize(raw: String): String = raw.lineSequence()
        .map { it.trim().removePrefix("- ").removePrefix("• ").removePrefix("* ") }
        .filter { it.isNotEmpty() }
        .joinToString(" ")
        .replace(WHITESPACE, " ")
        .trim()

    private fun trimToLimit(text: String): String {
        if (text.length <= MAX_CHARS) return text
        val cut = text.take(MAX_CHARS)
        val sentenceEnd = maxOf(cut.lastIndexOf('.'), cut.lastIndexOf('!'), cut.lastIndexOf('?'))
        return if (sentenceEnd > MIN_CHARS) cut.take(sentenceEnd + 1) else cut.trimEnd() + "…"
    }

    private fun isCyrillic(char: Char): Boolean = char in 'а'..'я' || char in 'А'..'Я' || char == 'ё' || char == 'Ё'
}
