package com.example.speech.impl

import com.example.speech.api.TextCorrector
import com.example.speech.api.TextGenerator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Корректор на локальной LLM: правит ослышки и опечатки в готовом тексте
 * распознавания.
 *
 * Устройство и почему так:
 * - числа прячутся за плейсхолдерами ([EntityMasking]) — их модель портит
 *   незаметнее всего;
 * - ответ режется по бюджету времени: не успел — отдаём исходный текст, а не
 *   задерживаем показ;
 * - ответ проходит [CorrectionGuard]: правка больше 10 % символов считается
 *   переписыванием и откатывается;
 * - пустой или испорченный ответ (потерян плейсхолдер, нет текста) — тоже
 *   откат к исходному.
 *
 * Модель не меняет спикеров и метки времени: [correct] работает только с
 * текстом одной реплики. Финальный проход по всей расшифровке — [TranscriptPostprocessor].
 *
 * @param maxOutputTokens потолок длины ответа; реплика в 30 с — это 25–40
 *   токенов входа и примерно столько же выхода, 96 хватает с запасом.
 * @param budgetMs бюджет на одну реплику. На 4-ГБ телефоне коррекция живёт в
 *   финальном проходе, где спешить некуда, но бюджет всё равно нужен: зависший
 *   генератор не должен держать очередь.
 */
class LlmCorrector(
    private val generator: TextGenerator,
    private val maxOutputTokens: Int = 96,
    private val budgetMs: Long = 1_200,
) : TextCorrector {

    init {
        require(maxOutputTokens >= 8) {
            "maxOutputTokens должен быть >= 8, получено $maxOutputTokens"
        }
        require(budgetMs > 0) {
            "budgetMs должен быть > 0, получено $budgetMs"
        }
    }

    override suspend fun load() = generator.load()

    override fun release() = generator.release()

    override suspend fun correct(text: String): String {
        if (text.isBlank()) return text

        val masked = EntityMasking.mask(text)
        val generated = try {
            withTimeoutOrNull(budgetMs) {
                generator.generate(buildPrompt(masked.text), maxOutputTokens)
            }
        } catch (c: CancellationException) {
            throw c
        } catch (_: Throwable) {
            // Упавшая модель не должна убивать расшифровку: текст валиден и без неё.
            null
        } ?: return text

        val candidate = CorrectionOutputParser.parse(generated) ?: return text
        val restored = masked.restore(candidate) ?: return text

        return if (CorrectionGuard.accepts(text, restored)) restored else text
    }

    internal companion object {
        private val FIXED_TAG = Regex("<fixed>(.*?)</fixed>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

        /**
         * Промпт на две фразы: роль и запрет на «улучшения». Просьба вернуть
         * теги делает разбор ответа однозначным, а если модель их проигнорирует,
         * [CorrectionOutputParser] возьмёт текст как есть — гард всё равно
         * проверит дистанцию.
         *
         * Два примера — не украшение: без них Qwen2.5-0.5B на пустой инструкции
         * переписывает фразу по смыслу («я придёт завтра в девять» →
         * «я буду работать завтра в десять»). С примерами, где один ответ
         * исправляет слово, а второй повторяет фразу без правок, модель
         * возвращает текст дословно и не выдумывает. Проверено на эмуляторе,
         * Pixel_8_API36, 2026-11-07.
         */
        fun buildPrompt(text: String): String = buildString {
            appendLine("Ты исправляешь ошибки распознавания речи.")
            appendLine(
                "Верни ту же фразу, исправив только явные ошибки в словах: ослышки, " +
                    "опечатки, неверные окончания. Не добавляй и не удаляй слова, не меняй " +
                    "порядок слов и смысл. Числа, имена и плейсхолдеры вида [[0]] оставь без изменений.",
            )
            appendLine("Ответь одной строкой, обернув результат в теги <fixed></fixed>.")
            appendLine()
            appendLine("Пример:")
            appendLine("Фраза: он придёт завтра")
            appendLine("Ответ: <fixed>он приедет завтра</fixed>")
            appendLine()
            appendLine("Пример:")
            appendLine("Фраза: я буду работать завтра в десять")
            appendLine("Ответ: <fixed>я буду работать завтра в десять</fixed>")
            appendLine()
            append("Фраза: ")
            append(text)
        }

        fun parseFixedTag(raw: String): String? =
            FIXED_TAG.find(raw)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
    }
}

/**
 * Достаёт текст из ответа генератора. Пробует тег `<fixed>`, иначе берёт первую
 * непустую строку без кавычек и служебных префиксов.
 *
 * Любые HTML-подобные теги вычищаются: просьба обернуть ответ в `<fixed>`
 * переключает маленькую модель в «HTML-режим», и она добавляет `<br>`/`</br>`.
 * Гард их не ловит — после нормализации от тега остаются только буквы «br», а это
 * меньше 10 % длины длинной фразы, — и теги попадали в расшифровку.
 */
internal object CorrectionOutputParser {

    private val MARKUP = Regex("""</?\s*[A-Za-z][A-Za-z0-9]*\s*/?\s*>""")
    private val SPACES = Regex("""\s+""")

    fun clean(text: String): String = text.replace(MARKUP, " ").replace(SPACES, " ").trim()

    private val PREFIXES = listOf(
        "исправленный текст:",
        "исправленная фраза:",
        "результат:",
        "текст:",
    )

    fun parse(raw: String): String? {
        LlmCorrector.parseFixedTag(raw)?.let { tagged -> clean(tagged).takeIf { it.isNotEmpty() }?.let { return it } }

        val line = raw.lineSequence()
            .map { clean(it) }
            .firstOrNull { it.isNotEmpty() }
            ?: return null

        val stripped = PREFIXES
            .firstOrNull { line.lowercase().startsWith(it) }
            ?.let { line.substring(it.length).trim() }
            ?: line

        return stripped
            .trim('"', '\'', '«', '»')
            .trim()
            .takeIf { it.isNotEmpty() }
    }
}
