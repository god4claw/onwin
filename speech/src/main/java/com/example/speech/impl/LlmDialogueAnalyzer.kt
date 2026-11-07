package com.example.speech.impl

import com.example.speech.api.DialogueNotes
import com.example.speech.api.ProtocolAction
import com.example.speech.api.ProtocolAnalyzer
import com.example.speech.api.ProtocolNotes
import com.example.speech.api.ProtocolParticipant
import com.example.speech.api.Segment
import com.example.speech.api.TextGenerator
import com.example.speech.api.TranscriptAnalyzer
import com.example.speech.api.TranscriptLanguage
import com.example.speech.api.detectTranscriptLanguage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [TranscriptAnalyzer] и [ProtocolAnalyzer] на локальной LLM: имена спикеров,
 * краткое резюме и опциональный протокол по всему диалогу.
 *
 * Устройство и почему так:
 * - диалог режется на куски по [maxCharsPerChunk], имена собираются по всем
 *   кускам: имя, названное один раз в середине разговора, иначе не найти;
 * - имя принимается, только если оно не спорит с другими кусками (при равенстве
 *   голосов — отказ): выдуманное имя хуже, чем никакого;
 * - резюме — один запрос по сокращённой расшифровке, с валидацией: пустое,
 *   слишком короткое, похожее на эхо промпта или без букв нужного языка отбрасывается;
 * - протокол — отдельный опциональный запрос: участники берутся из сегментов и
 *   уже извлечённых имён, модель пишет только summary/decisions/actions;
 * - язык определяется по тексту и выбирает промпт: русский, английский или
 *   смешанный (русская инструкция + «термины не переводи»);
 * - бюджет времени на каждый запрос: зависшая модель не держит очередь;
 * - любая ошибка генератора — это `null`, а не исключение: расшифровка важнее.
 *
 * @param maxCharsPerChunk бюджет символов на один кусок диалога при сборе имён.
 * @param maxCharsForSummary бюджет символов для запроса резюме (начало и конец).
 * @param maxOutputTokens потолок ответа модели для резюме.
 * @param budgetMs бюджет на один запрос анализа.
 * @param maxCharsForProtocol бюджет символов для запроса протокола.
 * @param protocolMaxOutputTokens потолок ответа модели для протокола.
 * @param protocolBudgetMs бюджет на запрос протокола.
 */
class LlmDialogueAnalyzer(
    private val generator: TextGenerator,
    private val maxCharsPerChunk: Int = 3_000,
    private val maxCharsForSummary: Int = 6_000,
    private val maxOutputTokens: Int = 320,
    private val budgetMs: Long = 20_000,
    private val maxCharsForProtocol: Int = 6_000,
    private val protocolMaxOutputTokens: Int = 420,
    private val protocolBudgetMs: Long = 60_000,
) : TranscriptAnalyzer, ProtocolAnalyzer {

    init {
        require(maxCharsPerChunk > 0) { "maxCharsPerChunk должен быть > 0, получено $maxCharsPerChunk" }
        require(maxCharsForSummary > 0) { "maxCharsForSummary должен быть > 0, получено $maxCharsForSummary" }
        require(maxOutputTokens > 0) { "maxOutputTokens должен быть > 0, получено $maxOutputTokens" }
        require(budgetMs > 0) { "budgetMs должен быть > 0, получено $budgetMs" }
        require(maxCharsForProtocol > 0) { "maxCharsForProtocol должен быть > 0, получено $maxCharsForProtocol" }
        require(protocolMaxOutputTokens > 0) { "protocolMaxOutputTokens должен быть > 0" }
        require(protocolBudgetMs > 0) { "protocolBudgetMs должен быть > 0" }
    }

    override suspend fun load() = generator.load()

    override fun release() = generator.release()

    override suspend fun analyze(segments: List<Segment>): DialogueNotes? {
        val dialogue = segments.filter { it.text.isNotBlank() }
        if (dialogue.isEmpty()) return null

        val language = detectTranscriptLanguage(dialogue.joinToString(" ") { it.text })
        val notes = DialogueNotes(
            speakerNames = collectNames(dialogue, language),
            summary = summarize(dialogue, language),
            language = language,
        )
        return notes.takeUnless { it.isEmpty }
    }

    override suspend fun analyzeProtocol(
        segments: List<Segment>,
        speakerNames: Map<Int, String>,
    ): ProtocolNotes? {
        val dialogue = segments.filter { it.text.isNotBlank() }
        if (dialogue.isEmpty()) return null

        val names = speakerNames.filterValues { it.isNotBlank() }
        val language = detectTranscriptLanguage(dialogue.joinToString(" ") { it.text })
        val participants = dialogue.map { it.speaker }.distinct().sorted().map { speaker ->
            ProtocolParticipant(
                speaker = speaker,
                speakerId = "speaker_${speaker + 1}",
                name = names[speaker].orEmpty(),
            )
        }
        val transcript = DialogueText.forModel(
            dialogue,
            limit = maxCharsForProtocol,
            names = names,
            english = language == TranscriptLanguage.EN,
        )
        val prompt = buildString {
            appendLine(protocolRules(language))
            appendLine()
            append(transcript)
        }
        val answer = generate(prompt, protocolMaxOutputTokens, protocolBudgetMs) ?: return null
        val parsed = ProtocolParser.parse(answer, language) ?: return null
        val notes = ProtocolNotes(
            participants = participants,
            summary = parsed.summary,
            decisions = parsed.decisions,
            actions = parsed.actions,
            language = language,
        )
        return notes.takeUnless { it.isEmpty }
    }

    /** Имена по всем кускам: побеждает имя с большинством голосов, ничья — отказ. */
    private suspend fun collectNames(
        segments: List<Segment>,
        language: TranscriptLanguage,
    ): Map<Int, String> {
        val chunks = DialogueChunker.chunk(segments, maxCharsPerChunk)
        val votes = mutableMapOf<Int, MutableMap<String, Int>>()

        for (chunk in chunks) {
            val prompt = buildString {
                appendLine(nameRules(language))
                appendLine()
                append(DialogueText.forModel(chunk, english = language == TranscriptLanguage.EN))
            }
            val answer = generate(prompt, maxOutputTokens, budgetMs) ?: continue
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
    private suspend fun summarize(
        segments: List<Segment>,
        language: TranscriptLanguage,
    ): String {
        val transcript = DialogueText.forModel(
            segments,
            limit = maxCharsForSummary,
            english = language == TranscriptLanguage.EN,
        )
        val prompt = buildString {
            appendLine(summaryRules(language))
            appendLine()
            append(transcript)
        }
        return SummarySanitizer.clean(generate(prompt, maxOutputTokens, budgetMs) ?: "", language)
    }

    /** Один запрос к модели; ошибка или таймаут — `null`, отмена — пробрасывается. */
    private suspend fun generate(prompt: String, maxTokens: Int, timeoutMs: Long): String? = try {
        withTimeoutOrNull(timeoutMs) { generator.generate(prompt, maxTokens) }
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

        val NAME_RULES_EN: String = """
            You read a conversation transcript and extract participant names.
            For every speaker whose name or role is explicitly stated, return a line "number: name".
            Use only what is said in the dialogue: "my name is Anna", "this is Sergey", "I am a doctor".
            If a name is not stated, do not write a line for that speaker.
            Do not invent anything, do not add explanations or headings.
        """.trimIndent()

        val SUMMARY_RULES_EN: String = """
            You write a short summary of a conversation in English.
            Return 3–5 sentences: what was discussed, what was decided, what still needs to be done.
            Facts from the dialogue only, no inventions, no headings and no address to the reader.
        """.trimIndent()

        val PROTOCOL_RULES: String = """
            Ты — секретарь заседания. Составь протокол по расшифровке разговора.
            Верни ровно три секции:
            КРАТКО:
            <2–3 предложения о теме и итоге>
            РЕШЕНИЯ:
            - <принятое решение>
            ПОРУЧЕНИЯ:
            - <задача> | <ответственный> | <срок>
            Если решений или поручений нет, оставь секцию пустой.
            Не выдумывай имена, даты и числа. Не пересказывай реплики.
        """.trimIndent()

        val PROTOCOL_RULES_EN: String = """
            You are a meeting secretary. Build meeting minutes from the transcript.
            Return exactly three sections:
            SUMMARY:
            <2–3 sentences about the topic and outcome>
            DECISIONS:
            - <decision>
            ACTIONS:
            - <task> | <owner> | <due>
            If there are no decisions or actions, leave the section empty.
            Do not invent names, dates or numbers. Do not retell the dialogue.
        """.trimIndent()

        private const val MIXED_NOTE =
            "Не переводи английские термины и имена; пиши на языке большинства реплик."

        fun nameRules(language: TranscriptLanguage): String = when (language) {
            TranscriptLanguage.EN -> NAME_RULES_EN
            TranscriptLanguage.MIXED -> NAME_RULES + "\n" + MIXED_NOTE
            else -> NAME_RULES
        }

        fun summaryRules(language: TranscriptLanguage): String = when (language) {
            TranscriptLanguage.EN -> SUMMARY_RULES_EN
            TranscriptLanguage.MIXED -> SUMMARY_RULES + "\n" + MIXED_NOTE
            else -> SUMMARY_RULES
        }

        fun protocolRules(language: TranscriptLanguage): String = when (language) {
            TranscriptLanguage.EN -> PROTOCOL_RULES_EN
            TranscriptLanguage.MIXED -> PROTOCOL_RULES + "\n" + MIXED_NOTE
            else -> PROTOCOL_RULES
        }
    }
}

/** Диалог в виде, который читает модель: `Спикер N: текст` или `Speaker N (Имя): текст`. */
internal object DialogueText {

    /**
     * @param limit бюджет символов; при превышении берётся начало и конец
     *   с многоточием — середина длинного разговора для резюме не критична.
     * @param names подписи спикеров, если уже известны.
     * @param english английские подписи `Speaker` вместо `Спикер`.
     */
    fun forModel(
        segments: List<Segment>,
        limit: Int = Int.MAX_VALUE,
        names: Map<Int, String> = emptyMap(),
        english: Boolean = false,
    ): String {
        val label = if (english) "Speaker " else "Спикер "
        val full = buildString {
            segments.forEachIndexed { index, segment ->
                if (index > 0) append('\n')
                append(label)
                append(segment.speaker + 1)
                names[segment.speaker]?.takeIf { it.isNotBlank() }?.let {
                    append(" (")
                    append(it)
                    append(')')
                }
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
 * Проверяет резюме: пустое, короткое, похожее на эхо промпта или без букв
 * нужного языка отбрасывается. Длинное обрезается по границе предложения.
 */
internal object SummarySanitizer {

    private const val MIN_CHARS = 40
    private const val MAX_CHARS = 800
    private val WHITESPACE = Regex("\\s+")
    private val SPEAKER_ECHO = Regex("""(?:спикер|speaker)\s*\d+\s*:""", RegexOption.IGNORE_CASE)

    fun clean(raw: String, language: TranscriptLanguage = TranscriptLanguage.RU): String {
        val text = normalize(raw)
        if (text.length < MIN_CHARS) return ""
        if (text.contains('|')) return ""
        if (SPEAKER_ECHO.containsMatchIn(text)) return ""
        val letters = text.count { it.isLetter() }
        if (letters < 10) return ""
        if (language != TranscriptLanguage.EN && text.count(::isCyrillic) < 10) return ""
        if (language == TranscriptLanguage.EN && text.count(::isLatin) < 10) return ""
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

    private fun isLatin(char: Char): Boolean = char in 'a'..'z' || char in 'A'..'Z'
}

/** Разобранный протокол без участников: их собирает анализатор из сегментов. */
internal data class ParsedProtocol(
    val summary: String,
    val decisions: List<String>,
    val actions: List<ProtocolAction>,
)

/**
 * Разбирает ответ модели на секции `КРАТКО` / `РЕШЕНИЯ` / `ПОРУЧЕНИЯ`
 * (или `SUMMARY` / `DECISIONS` / `ACTIONS`).
 *
 * Почему не JSON: ONNX Runtime GenAI 0.19 не умеет GBNF, а 0.5B на JSON
 * ломается. Секции простым текстом парсятся надёжнее, а JSON для экспорта
 * собирается уже в Kotlin.
 */
internal object ProtocolParser {

    private enum class Section { SUMMARY, DECISIONS, ACTIONS }

    private val HEADING = Regex(
        """^\s*(КРАТКО|РЕЗЮМЕ|РЕШЕНИЯ|ПОРУЧЕНИЯ|SUMMARY|DECISIONS|ACTIONS|ACTION\s+ITEMS)\s*:?\s*(.*)$""",
        RegexOption.IGNORE_CASE,
    )
    private val BULLET = Regex("""^\s*(?:[-*•]|\d+[.)])\s*""")
    private val WHITESPACE = Regex("\\s+")
    private val SPEAKER_ECHO = Regex("""(?:спикер|speaker)\s*\d+\s*:""", RegexOption.IGNORE_CASE)

    fun parse(raw: String, language: TranscriptLanguage): ParsedProtocol? {
        val sections = linkedMapOf<Section, MutableList<String>>()
        var current: Section? = null
        raw.lineSequence().forEach { line ->
            val heading = HEADING.find(line)
            if (heading != null) {
                current = when (heading.groupValues[1].uppercase().replace('Ё', 'Е')) {
                    "КРАТКО", "РЕЗЮМЕ", "SUMMARY" -> Section.SUMMARY
                    "РЕШЕНИЯ", "DECISIONS" -> Section.DECISIONS
                    else -> Section.ACTIONS
                }
                val bucket = sections.getOrPut(current!!) { mutableListOf() }
                heading.groupValues[2].trim().takeIf { it.isNotEmpty() }?.let(bucket::add)
            } else if (current != null) {
                sections.getOrPut(current!!) { mutableListOf() }.add(line)
            }
        }
        if (sections.isEmpty()) return null

        val summary = cleanSummary(sections[Section.SUMMARY].orEmpty(), language)
        val decisions = cleanItems(sections[Section.DECISIONS].orEmpty(), limit = 12, maxLength = 300)
        val actions = cleanActions(sections[Section.ACTIONS].orEmpty())
        if (summary.isBlank() && decisions.isEmpty() && actions.isEmpty()) return null
        return ParsedProtocol(summary = summary, decisions = decisions, actions = actions)
    }

    private fun cleanSummary(lines: List<String>, language: TranscriptLanguage): String {
        val text = lines.asSequence()
            .map { it.replace(BULLET, "").trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .replace(WHITESPACE, " ")
            .trim()
        if (text.length < 15 || text.contains('|') || SPEAKER_ECHO.containsMatchIn(text)) return ""
        val letters = text.count { it.isLetter() }
        if (letters < 8) return ""
        if (language == TranscriptLanguage.EN && text.count { it in 'a'..'z' || it in 'A'..'Z' } < 5) return ""
        if (language != TranscriptLanguage.EN && text.count { it in 'а'..'я' || it in 'А'..'Я' || it == 'ё' || it == 'Ё' } < 5) return ""
        return text.take(600)
    }

    private fun cleanItems(lines: List<String>, limit: Int, maxLength: Int): List<String> {
        val result = LinkedHashMap<String, String>()
        for (line in lines) {
            val item = line.replace(BULLET, "").trim().replace(WHITESPACE, " ")
            if (item.length !in 3..maxLength) continue
            if (item.contains('|') || SPEAKER_ECHO.containsMatchIn(item)) continue
            val key = item.lowercase()
            result.putIfAbsent(key, item)
            if (result.size >= limit) break
        }
        return result.values.toList()
    }

    private fun cleanActions(lines: List<String>): List<ProtocolAction> {
        val result = LinkedHashMap<String, ProtocolAction>()
        for (line in lines) {
            val item = line.replace(BULLET, "").trim()
            if (item.isEmpty() || SPEAKER_ECHO.containsMatchIn(item)) continue
            val parts = item.split('|').map { it.trim() }
            val task = parts.getOrNull(0).orEmpty()
            if (task.length !in 3..300) continue
            val action = ProtocolAction(
                task = task,
                owner = parts.getOrNull(1).orEmpty().take(60),
                due = parts.getOrNull(2).orEmpty().take(60),
            )
            result.putIfAbsent(task.lowercase(), action)
            if (result.size >= 12) break
        }
        return result.values.toList()
    }
}
