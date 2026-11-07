package com.example.speech.impl

/**
 * Английская речь без выбора языка: решение — по выводу GigaAM.
 *
 * GigaAM русская; английскую речь она пишет латиницей «на слух» («Ask now wey
 * your cuntry…»), русскую — кириллицей, бренды в русской фразе — одним-двумя
 * латинскими словами. Поэтому:
 *
 * 1. Если в куске больше [WHOLE_LATIN_SHARE] букв латиницей (и их не меньше
 *    [WHOLE_MIN_LATIN]), весь кусок распознаётся английской моделью.
 * 2. Иначе каждый ряд из ≥ [SPAN_MIN_WORDS] латинских слов подряд (≥
 *    [SPAN_MIN_LETTERS] букв) — английская фраза внутри куска, например рядом
 *    с русским ответом другого человека: этот участок аудио (± 0,3 с, не
 *    заходя на соседние слова) распознаётся английской моделью, её слова
 *    заменяют слова GigaAM.
 *
 * На хосте (одинаковая VAD-нарезка): английский LibriSpeech 69,9 → 5,7 % WER,
 * смешанные RU+EN диалоги 17,5 → 7,1 %, 70 русских файлов — ни одного
 * срабатывания, WER без изменений.
 *
 * @param englishWords распознавание английской моделью; слова — в сэмплах
 *   относительно переданного куска.
 */
internal class EnglishRouter(
    private val englishWords: (FloatArray) -> List<AsrWord>,
    private val sampleRate: Int,
    private val trace: ((String) -> Unit)? = null,
) {
    fun route(samples: FloatArray, words: List<AsrWord>): List<AsrWord> {
        if (words.isEmpty()) return words
        var latin = 0
        var cyrillic = 0
        for (word in words) {
            latin += word.text.count(::isLatinLetter)
            cyrillic += word.text.count(::isCyrillicLetter)
        }
        if (latin >= WHOLE_MIN_LATIN && latin > WHOLE_LATIN_SHARE * (latin + cyrillic)) {
            val english = englishWords(samples)
            trace?.invoke("english chunk dur=${samples.size * 1000L / sampleRate}ms words=${words.size}->${english.size}")
            return english.ifEmpty { words }
        }
        if (latin < SPAN_MIN_LETTERS) return words

        val out = ArrayList<AsrWord>(words.size)
        var start = 0
        while (start < words.size) {
            var end = start
            while (end < words.size && isLatinWord(words[end].text)) end++
            val letters = (start until end).sumOf { index -> words[index].text.count(::isLatinLetter) }
            if (end - start >= SPAN_MIN_WORDS && letters >= SPAN_MIN_LETTERS) {
                val pad = sampleRate * SPAN_PAD_MS / 1000
                val left = if (start > 0) words[start - 1].endSample else 0
                val right = if (end < words.size) words[end].startSample else samples.size
                val from = maxOf(left, words[start].startSample - pad).coerceIn(0, samples.size)
                val to = minOf(right, words[end - 1].endSample + pad).coerceIn(from, samples.size)
                val english = if (to > from) englishWords(samples.copyOfRange(from, to)) else emptyList()
                trace?.invoke(
                    "english span at=${from * 1000L / sampleRate}ms dur=${(to - from) * 1000L / sampleRate}ms " +
                        "words=${end - start}->${english.size}",
                )
                if (english.isEmpty()) {
                    out += words.subList(start, end)
                } else {
                    english.mapTo(out) { it.copy(startSample = it.startSample + from, endSample = it.endSample + from) }
                }
                start = end
            } else {
                out += words[start]
                start++
            }
        }
        return out
    }

    internal companion object {
        const val WHOLE_LATIN_SHARE = 0.9f
        const val WHOLE_MIN_LATIN = 8
        const val SPAN_MIN_WORDS = 4
        const val SPAN_MIN_LETTERS = 15
        const val SPAN_PAD_MS = 300

        private fun isLatinLetter(c: Char) = c in 'a'..'z' || c in 'A'..'Z'

        private fun isCyrillicLetter(c: Char) = c in 'а'..'я' || c in 'А'..'Я' || c == 'ё' || c == 'Ё'

        private fun isLatinWord(text: String) = text.any(::isLatinLetter) && text.none(::isCyrillicLetter)
    }
}
