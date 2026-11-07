package com.example.speech.api

/** Язык расшифровки, на котором говорят участники. */
enum class TranscriptLanguage {
    RU,
    EN,

    /** Есть и кириллица, и латиница в заметных долях. */
    MIXED,

    /** Слишком короткий или небуквенный текст. */
    UNKNOWN,
}

/**
 * Определяет язык по буквам текста. Без модели и зависимостей: для выбора промпта
 * и бейджа качества этого достаточно.
 *
 * Порог 20 букв отсекает короткие «да», «ok» и шум; на полном диалоге он не мешает.
 */
fun detectTranscriptLanguage(text: String): TranscriptLanguage {
    var cyrillic = 0
    var latin = 0
    for (char in text) {
        when {
            char in 'а'..'я' || char in 'А'..'Я' || char == 'ё' || char == 'Ё' -> cyrillic++
            char in 'a'..'z' || char in 'A'..'Z' -> latin++
        }
    }
    val total = cyrillic + latin
    if (total < 20) return TranscriptLanguage.UNKNOWN
    val cyrillicShare = cyrillic.toDouble() / total
    return when {
        cyrillicShare >= 0.75 -> TranscriptLanguage.RU
        cyrillicShare <= 0.25 -> TranscriptLanguage.EN
        else -> TranscriptLanguage.MIXED
    }
}
