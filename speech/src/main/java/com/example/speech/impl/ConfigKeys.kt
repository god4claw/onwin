package com.example.speech.impl

import com.example.speech.api.SpeechConfig

/**
 * Поля конфига, которые влияют на загруженные native-модели.
 *
 * `SpeechConfig` — data class, и его `equals` сравнивает всё подряд, включая
 * параметры, которые меняются от задания к заданию (порог онлайн-кластеризации,
 * размеры pruning). Если сравнивать конфиги целиком, любое такое изменение
 * перезагружает 350 МБ весов (5–10 с).
 *
 * Поэтому `load()` сравнивает не весь конфиг, а только «ключ моделей», и
 * запоминает последний конфиг целиком — runtime-параметры применяются сразу,
 * без перезагрузки.
 */
internal fun SpeechConfig.batchModelsMatch(other: SpeechConfig): Boolean =
    numThreads == other.numThreads &&
        asrModel == other.asrModel &&
        embeddingModel == other.embeddingModel &&
        autoClusterThreshold == other.autoClusterThreshold

/** То же для streaming: embedding-модель и параметры Silero VAD входят в ключ. */
internal fun SpeechConfig.streamingModelsMatch(other: SpeechConfig): Boolean =
    numThreads == other.numThreads &&
        asrModel == other.asrModel &&
        embeddingModel == other.embeddingModel &&
        vad == other.vad
