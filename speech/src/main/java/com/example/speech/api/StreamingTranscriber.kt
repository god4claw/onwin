package com.example.speech.api

import kotlinx.coroutines.flow.Flow

interface StreamingTranscriber {
    suspend fun load(config: SpeechConfig)

    fun transcribeStream(audio: Flow<FloatArray>): Flow<StreamingEvent>

    fun release()
}

sealed interface StreamingEvent {
    data class SpeechStarted(
        val startMs: Long
    ) : StreamingEvent
    data class Utterance(
        val segment: Segment
    ) : StreamingEvent
    data class Done(
        val segments: List<Segment>
    ) : StreamingEvent
    data class Failed(
        val cause: Throwable
    ) : StreamingEvent
}

/**
 * Автоматическая регулировка усиления (АРУ) перед VAD в streaming.
 *
 * Поднимает тихую речь к [targetDbfs], чтобы VAD не пропускал собеседника,
 * который сидит дальше от микрофона. Громкую речь не приглушает, фон не
 * раздувает: усиление растёт только на кадрах заметно громче фона, а в паузах
 * возвращается к 0 дБ. Работает только по прошлому сигналу, без задержки.
 * Выровненный сигнал видит только VAD; ASR и embedding получают исходный.
 *
 * @param targetDbfs уровень речи (RMS), к которому подтягивается тихий голос.
 * @param maxGainDb потолок усиления; выше — рискуем раздуть шум до «речи».
 */
data class AgcConfig(
    val enabled: Boolean = true,
    val targetDbfs: Float = -20f,
    val maxGainDb: Float = 24f
) {
    init {
        require(targetDbfs in -40f..-3f) { "targetDbfs должен быть в -40..-3, получено $targetDbfs" }
        require(maxGainDb in 0f..40f) { "maxGainDb должен быть в 0..40, получено $maxGainDb" }
    }
}

/**
 * Silero VAD. Порог 0,35 вместо стандартного 0,5: при 0,5 VAD терял речь в шуме
 * (белый шум 16 дБ — 13 удалённых слов из 40). На 45 синтетических разговорах,
 * включая 19, не участвовавших в подборе, 0,35 даёт меньше WER и ошибок
 * атрибуции спикеров; ниже (0,3) VAD начинает принимать шум за речь и плодит
 * лишних спикеров. См. docs/bench-vad-noise.md.
 */
data class VadConfig(
    val threshold: Float = 0.35f,
    val minSilenceMs: Int = 450,
    val minSpeechMs: Int = 250,
    val maxSpeechMs: Int = 25_000
) {
    init {
        require(threshold in 0f..1f) { "VAD threshold должен быть в 0..1, получено $threshold" }
        require(minSilenceMs >= 0) { "minSilenceMs должен быть >= 0" }
        require(minSpeechMs >= 0) { "minSpeechMs должен быть >= 0" }
        require(maxSpeechMs > minSpeechMs) { "maxSpeechMs должен быть больше minSpeechMs" }
    }
}
