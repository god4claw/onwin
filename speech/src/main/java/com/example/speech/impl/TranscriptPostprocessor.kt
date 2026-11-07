package com.example.speech.impl

import com.example.speech.api.Segment
import com.example.speech.api.TextCorrector

/**
 * Прогоняет корректор по готовой расшифровке: меняет только [Segment.text],
 * спикеры и метки времени остаются как были.
 *
 * Запускать это нужно **после** конца сессии распознавания и после
 * `release()` движка, а не внутри `Done`:
 *
 * - ASR держит в памяти GigaAM (~305 МБ) и embedding-модель; LLM рядом — это
 *   ещё ~450 МБ, и на 4-ГБ телефоне такой пик в момент записи кончается
 *   убийством процесса вместе с незаписанным аудио;
 * - `Done` пересобирает сегменты (склейка соседей, перенумерация спикеров),
 *   поэтому править текст до этого момента бессмысленно — часть правок уедет
 *   в склеенный текст, часть потеряется.
 *
 * Порядок по слоям: сначала [LexiconCorrector] (дёшево и точно), затем
 * [LlmCorrector] (дорого и с гардом). Оба реализуют [TextCorrector], так что
 * цепочка собирается в вызывающем коде.
 */
class TranscriptPostprocessor(private val corrector: TextCorrector) {

    /** Возвращает копию списка с исправленным текстом; порядок и метки не меняются. */
    suspend fun process(segments: List<Segment>): List<Segment> = segments.map { segment ->
        val text = corrector.correct(segment.text)
        if (text == segment.text) segment else segment.copy(text = text)
    }
}

/**
 * Цепочка корректоров: каждый следующий получает текст предыдущего.
 *
 * Гард стоит внутри [LlmCorrector], поэтому слои не могут «сложиться» в
 * суммарную правку больше порога — каждый проверяется отдельно от своего
 * входа.
 */
class CompositeCorrector(private val correctors: List<TextCorrector>) : TextCorrector {

    init {
        require(correctors.isNotEmpty()) { "Нужен хотя бы один корректор" }
    }

    override suspend fun load() {
        correctors.forEach { it.load() }
    }

    override fun release() {
        correctors.forEach { it.release() }
    }

    override suspend fun correct(text: String): String {
        var result = text
        for (corrector in correctors) {
            result = corrector.correct(result)
        }
        return result
    }
}
