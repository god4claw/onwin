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
 */
internal object CorrectionOutputParser {

    private val PREFIXES = listOf(
        "исправленный текст:",
        "исправленная фраза:",
        "результат:",
        "текст:",
    )

    fun parse(raw: String): String? {
        LlmCorrector.parseFixedTag(raw)?.let { return it }

        val line = raw.lineSequence()
            .map { it.trim() }
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
