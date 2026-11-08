package com.example.speech.impl

/**
 * Защита от «улучшений» корректора: принимает правку, только если она меняет
 * небольшую долю символов.
 *
 * Маленькая модель не знает, что было сказано, и охотно переписывает
 * правильно распознанное слово в правдоподобное. На русском это регулярно
 * повышает WER, потому что эталон сравнивается с вербатимом. Гард режет такие
 * ответы: правка в пару букв проходит, переписанная фраза — нет.
 *
 * Порог считается по нормализованной строке (регистр и пробелы не в счёт),
 * поэтому исправление «придёт» → «приедет» и расстановка заглавных не
 * наказываются.
 */
internal object CorrectionGuard {

    /**
     * Максимальная доля изменённых символов. 10 % — это примерно одна правка на
     * 10 символов: расстановка запятых и пара исправленных букв проходят, а
     * замена половины фразы — нет.
     */
    const val MAX_CHANGED_FRACTION = 0.10f

    /** `true`, если [corrected] можно принять вместо [source]. */
    fun accepts(source: String, corrected: String): Boolean {
        // Разметка, которой не было в исходной реплике, — мусор генератора, а не правка.
        if (MARKUP_CHARS.any { it in corrected && it !in source }) return false
        val original = normalize(source)
        val candidate = normalize(corrected)
        if (original.isEmpty() || candidate.isEmpty()) return false
        if (original == candidate) return true

        val allowed = maxOf(1, (original.length * MAX_CHANGED_FRACTION).toInt())
        return levenshtein(original, candidate) <= allowed
    }

    /** Регистр, пунктуация и повторные пробелы не считаются правкой. */
    internal fun normalize(text: String): String =
        text.lowercase()
            .replace(PUNCTUATION, " ")
            .replace(WHITESPACE, " ")
            .trim()

    /** Расстояние Левенштейна по символам, две строки памяти. */
    internal fun levenshtein(a: String, b: String): Int {
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)

        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(
                    current[j - 1] + 1,
                    previous[j] + 1,
                    substitution,
                )
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }

    private val WHITESPACE = Regex("\\s+")

    private val MARKUP_CHARS = charArrayOf('<', '>')

    /** Всё, что не буква, не цифра и не пробел: кавычки, запятые, тире. */
    private val PUNCTUATION = Regex("""[^\p{L}\p{M}\d\s]""")
}
