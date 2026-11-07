package com.example.speech.impl

/**
 * Прячет числа и проценты за плейсхолдерами на время генерации.
 *
 * Числа — то, что маленькая модель портит чаще всего и незаметнее всего:
 * «2026» превращается в «2025», «5 %» — в «пять процентов». Плейсхолдер
 * `[[0]]` переживает генерацию, а после неё значение возвращается как было.
 *
 * Если хоть один плейсхолдер пропал или появился лишний, [restore] возвращает
 * `null`: такой ответ целиком отбрасывается, текст остаётся исходным.
 */
internal class EntityMasking private constructor(
    /** Текст с плейсхолдерами вместо чисел — его и видит генератор. */
    val text: String,
    private val values: List<String>,
) {

    /**
     * Возвращает текст [candidate] с восстановленными числами или `null`, если
     * ответ генератора испортил плейсхолдеры.
     */
    fun restore(candidate: String): String? {
        if (values.isEmpty()) return candidate.takeIf { !it.contains(OPEN) }

        var restored = candidate
        for ((index, value) in values.withIndex()) {
            val placeholder = placeholder(index)
            if (!restored.contains(placeholder)) return null
            restored = restored.replace(placeholder, value)
        }
        // Остались чужие плейсхолдеры — генератор придумал свои, доверия нет.
        if (restored.contains(OPEN)) return null
        return restored
    }

    companion object {
        private const val OPEN = "[["

        /**
         * Числа, в том числе с дробной частью и процентом. Пробел входит в
         * совпадение только вместе с `%`, иначе маска съедала бы разделитель
         * слов: «2026 году» → «[[0]]году».
         */
        private val NUMBER = Regex("""\d+(?:[.,]\d+)?(?:\s?%)?""")

        fun mask(source: String): EntityMasking {
            val values = ArrayList<String>()
            val masked = NUMBER.replace(source) { match ->
                values += match.value
                placeholder(values.lastIndex)
            }
            return EntityMasking(masked, values)
        }

        internal fun placeholder(index: Int): String = "$OPEN$index]]"
    }
}
