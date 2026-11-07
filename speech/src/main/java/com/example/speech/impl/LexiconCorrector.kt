package com.example.speech.impl

import com.example.speech.api.TextCorrector

/**
 * Слой без модели: точная замена слов по словарю.
 *
 * Закрывает то, что LLM делает хуже и дороже, — устойчивые ослышки в именах,
 * брендах и терминах: «иванофф» → «Иванов», «кубернетес» → «Kubernetes»,
 * «телеграмм» → «Telegram». Работает за микросекунды, ничего не грузит и не
 * может испортить текст, кроме как заменить ровно то слово, которое просили.
 *
 * Словарь приходит снаружи (из настроек приложения или из карточки терминов) и
 * сопоставляется без учёта регистра. Регистр исходного слова сохраняется:
 * «Иванофф» → «Иванов», «ИВАНОФФ» → «ИВАНОВ».
 *
 * @param lexicon пары «как услышали» → «как правильно». Ключи короче двух
 *   символов игнорируются: замена одной буквы по такому словарю слишком легко
 *   портит обычные слова.
 */
class LexiconCorrector(lexicon: Map<String, String>) : TextCorrector {

    private val replacements: Map<String, String> = lexicon
        .filterKeys { it.trim().length >= MIN_KEY_LENGTH }
        .filterValues { it.isNotBlank() }
        .mapKeys { it.key.trim().lowercase() }

    override suspend fun load() = Unit

    override fun release() = Unit

    override suspend fun correct(text: String): String {
        if (text.isEmpty() || replacements.isEmpty()) return text

        return WORD.replace(text) { match ->
            val word = match.value
            val replacement = replacements[word.lowercase()] ?: return@replace word
            matchCase(word, replacement)
        }
    }

    /**
     * Сохраняет регистр исходного слова, но не ломает собственный регистр
     * словаря: имя «Иванов» остаётся с заглавной, даже если услышали «иванофф».
     */
    private fun matchCase(source: String, replacement: String): String = when {
        source.none { it.isLetter() } -> replacement
        source.all { !it.isLetter() || it.isUpperCase() } -> replacement.uppercase()
        source.first().isUpperCase() -> replacement.replaceFirstChar { it.uppercaseChar() }
        else -> replacement
    }

    private companion object {
        const val MIN_KEY_LENGTH = 2

        /** Слова, в том числе с дефисом; цифры не трогаем. */
        val WORD = Regex("""[\p{L}\p{M}]+(?:-[\p{L}\p{M}]+)*""")
    }
}
