package com.example.speech.api

/**
 * Разбор готовой расшифровки на уровне диалога: кто в нём участвует и о чём
 * был разговор.
 *
 * В отличие от [TextCorrector], который видит одну реплику и правит в ней
 * слова, анализатор получает весь диалог: имя, прозвучавшее один раз, можно
 * связать со спикером только по всему тексту.
 *
 * Контракт безопасный: любая неудача (нет имени, модель ответила мусором,
 * не уложилась в бюджет) — это `null` и пустые поля, а не исключение.
 * Расшифровка от этого не страдает.
 */
interface TranscriptAnalyzer {

    /** Готовит ресурсы (модель). Вызывать один раз перед работой. */
    suspend fun load()

    /**
     * Имена спикеров и краткое резюме диалога.
     *
     * @return заметки или `null`, если из диалога ничего достоверного не вышло.
     */
    suspend fun analyze(segments: List<Segment>): DialogueNotes?

    /** Освобождает ресурсы. */
    fun release()
}

/**
 * Что удалось вытащить из диалога.
 *
 * @property speakerNames номера спикеров → имена, которые прозвучали в тексте.
 *   Пустая карта — имён не называли.
 * @property summary резюме на 3–5 предложений; пустая строка — не получилось.
 * @property language язык, определённый по тексту; влияет на промпт и бейдж качества.
 */
data class DialogueNotes(
    val speakerNames: Map<Int, String> = emptyMap(),
    val summary: String = "",
    val language: TranscriptLanguage = TranscriptLanguage.UNKNOWN,
) {
    /** Есть ли что показывать пользователю. */
    val isEmpty: Boolean get() = speakerNames.isEmpty() && summary.isBlank()
}

/** Участник протокола. */
data class ProtocolParticipant(
    val speaker: Int,
    val speakerId: String,
    val name: String,
)

/** Поручение из протокола. */
data class ProtocolAction(
    val task: String,
    val owner: String = "",
    val due: String = "",
)

/**
 * Протокол заседания: то же саммари, но структурированное.
 *
 * @property participants все говорящие из расшифровки; имя пустое, если не названо.
 * @property summary краткое содержание на 2–3 предложения.
 * @property decisions принятые решения.
 * @property actions явные поручения.
 * @property language язык расшифровки.
 */
data class ProtocolNotes(
    val participants: List<ProtocolParticipant>,
    val summary: String,
    val decisions: List<String>,
    val actions: List<ProtocolAction>,
    val language: TranscriptLanguage = TranscriptLanguage.UNKNOWN,
) {
    val isEmpty: Boolean
        get() = summary.isBlank() && decisions.isEmpty() && actions.isEmpty()
}

/**
 * Опциональный проход по готовому диалогу: протокол.
 *
 * Отделён от [TranscriptAnalyzer], потому что запускается по кнопке, а не вместе
 * с расшифровкой, и имеет собственное хранилище/экран.
 */
interface ProtocolAnalyzer {

    /** Готовит модель; вызывается перед первым протоколом. */
    suspend fun load()

    /**
     * @param speakerNames имена, уже извлечённые [TranscriptAnalyzer]; участники
     *   собираются из них и сегментов, без повторного запроса к модели.
     */
    suspend fun analyzeProtocol(
        segments: List<Segment>,
        speakerNames: Map<Int, String> = emptyMap(),
    ): ProtocolNotes?

    /** Освобождает модель. */
    fun release()
}
