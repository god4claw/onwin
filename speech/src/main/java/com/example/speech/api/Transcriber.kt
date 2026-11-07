package com.example.speech.api

import kotlinx.coroutines.flow.Flow
import java.io.File

interface Transcriber {
    suspend fun load(config: SpeechConfig)

    /**
     * Расшифровка WAV.
     *
     * @param expectedSpeakers `null` — автоопределение, 1..20 — известное число
     *   участников. При `1` диаризация пропускается целиком: весь файл считается
     *   одной репликой одного говорящего, и batch работает в разы быстрее.
     */
    fun transcribe(
        wav: File,
        expectedSpeakers: Int?
    ): Flow<TranscriptionEvent>

    /** То же без подсказки о числе участников (авторежим). */
    fun transcribe(wav: File): Flow<TranscriptionEvent> = transcribe(wav, null)

    /**
     * Освобождает native-модели.
     *
     * Если в этот момент идёт расшифровка, освобождение откладывается до её
     * конца: освобождать native-объекты под работающим JNI-вызовом нельзя.
     */
    fun release()
}

/**
 * Модель speaker embedding для диаризации.
 *
 * - [CAMPLUS] — 3D-Speaker CAM++, обучена на китайской речи (zh-cn);
 * - [ERES2NET] — 3D-Speaker ERes2Net на VoxCeleb (тысячи дикторов, много
 *   языков), по умолчанию. На настоящих русских голосах различает похожие
 *   голоса одного пола: AUC 0,93 против 0,80 у CAM++; ошибка атрибуции на
 *   живых диалогах 23 → 4,5 % (docs/bench-eres2net.md). Пороги
 *   `speakerReclusterThreshold`/`speakerMinShare` по умолчанию подобраны под
 *   неё; для CAM++ были 0,60 / 0.
 */
enum class EmbeddingModel(
    val fileName: String
) {
    CAMPLUS("speaker_embedding_camplus.onnx"),
    ERES2NET("speaker_embedding_eres2net.onnx")
}

/**
 * Модель распознавания GigaAM-v3 e2e: пунктуация, регистр, числа цифрами.
 *
 * - [E2E_RNNT] — RNN-T-декодер (экспорт k2-fsa 2025-12-16), по умолчанию. На
 *   эмуляторе в режиме файла против [E2E_CTC]: WER живых диалогов 3,81 → 3,70 %,
 *   синтетики 16,2 → 15,1 %, val2 19,5 → 17,8 %; числа верно по значению 85 → 93 %;
 *   диаризация без изменений; RTF примерно на 17 % выше (docs/bench-asr-rnnt.md).
 * - [E2E_CTC] — прежняя CTC-модель; её файлы в APK необязательны.
 */
enum class AsrModel {
    E2E_RNNT,
    E2E_CTC
}

data class SpeechConfig(
    val numThreads: Int = 4,
    val asrModel: AsrModel = AsrModel.E2E_RNNT,
    /**
     * Английская речь без выбора языка: куски и фразы, которые GigaAM пишет в
     * основном латиницей, перераспознаются английской моделью Parakeet TDT 110M.
     * Модель грузится при первой английской фразе. Нет её файлов в APK — только
     * русский, без ошибки.
     */
    val detectEnglish: Boolean = true,
    val embeddingModel: EmbeddingModel = EmbeddingModel.ERES2NET,
    val autoClusterThreshold: Float = 0.80f,
    val onlineClusterThreshold: Float = 0.45f,
    val maxOnlineSpeakers: Int = 20,
    val vad: VadConfig = VadConfig(),
    /** АРУ перед VAD, только streaming. Batch не затрагивает. */
    val agc: AgcConfig = AgcConfig(),
    /**
     * Streaming: порог косинуса для финальной перекластеризации всех реплик
     * сессии в `Done` — выше → больше спикеров. Онлайн-метки в `Utterance`
     * предварительные, итоговые приходят в `Done`.
     */
    val speakerReclusterThreshold: Float = 0.65f,
    /**
     * Streaming: спикер, на которого в `Done` приходится меньше этой доли речи,
     * вливается в самый похожий (0 — выключено). ERes2Net дробит эмоциональную
     * речь одного человека на несколько кластеров.
     */
    val speakerMinShare: Float = 0.15f,
    /** Минимальная длительность кластера, который считается настоящим спикером (batch). */
    val clusterPruneMinMs: Int = 3_000,
    /** Минимальная доля речи для кластера (0..1); защищает от мелких кластеров. */
    val clusterPruneMinFraction: Float = 0.05f,
    /** Порог косинуса для смены говорящего внутри VAD-чанка. */
    val speakerChangeThreshold: Float = 0.65f,
    /** Шаг окон CAM++ при поиске смены говорящего внутри VAD-чанка. */
    val speakerChangeHopMs: Int = 800,
    /** Длина окна для CAM++ при поиске смены говорящего. */
    val speakerChangeWindowMs: Int = 1_600,
    /** Минимальная длина куска после разреза по смене говорящего. */
    val speakerChangeMinSegmentMs: Int = 1_500
) {
    init {
        require(numThreads >= 1) {
            "numThreads должен быть >= 1, получено $numThreads"
        }
        require(autoClusterThreshold in 0f..1f) {
            "autoClusterThreshold должен быть в 0..1, получено $autoClusterThreshold"
        }
        require(onlineClusterThreshold in 0f..1f) {
            "onlineClusterThreshold должен быть в 0..1, получено $onlineClusterThreshold"
        }
        require(maxOnlineSpeakers >= 1) {
            "maxOnlineSpeakers должен быть >= 1, получено $maxOnlineSpeakers"
        }
        require(clusterPruneMinMs >= 0) {
            "clusterPruneMinMs должен быть >= 0, получено $clusterPruneMinMs"
        }
        require(clusterPruneMinFraction in 0f..1f) {
            "clusterPruneMinFraction должен быть в 0..1, получено $clusterPruneMinFraction"
        }
        require(speakerChangeThreshold in 0f..1f) {
            "speakerChangeThreshold должен быть в 0..1, получено $speakerChangeThreshold"
        }
        require(speakerChangeHopMs > 0) {
            "speakerChangeHopMs должен быть > 0, получено $speakerChangeHopMs"
        }
        require(speakerChangeWindowMs > 0) {
            "speakerChangeWindowMs должен быть > 0, получено $speakerChangeWindowMs"
        }
        require(speakerMinShare in 0f..0.5f) {
            "speakerMinShare должен быть в 0..0,5, получено $speakerMinShare"
        }
        require(speakerReclusterThreshold in 0f..1f) {
            "speakerReclusterThreshold должен быть в 0..1, получено $speakerReclusterThreshold"
        }
        require(speakerChangeMinSegmentMs >= 0) {
            "speakerChangeMinSegmentMs должен быть >= 0, получено $speakerChangeMinSegmentMs"
        }
    }
}

data class Segment(
    val speaker: Int,
    val startMs: Long,
    val endMs: Long,
    val text: String
)

enum class Stage { DIARIZATION, RECOGNITION }

sealed interface TranscriptionEvent {
    data class Progress(
        val stage: Stage,
        val fraction: Float
    ) : TranscriptionEvent
    data class SegmentReady(
        val segment: Segment
    ) : TranscriptionEvent
    data class Done(
        val segments: List<Segment>
    ) : TranscriptionEvent
    data class Failed(
        val cause: Throwable
    ) : TranscriptionEvent
}
