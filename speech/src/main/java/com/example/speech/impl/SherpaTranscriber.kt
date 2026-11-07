package com.example.speech.impl

import android.content.Context
import android.content.res.AssetManager
import androidx.annotation.Keep
import com.example.speech.api.Segment
import com.example.speech.api.SpeechConfig
import com.example.speech.api.Stage
import com.example.speech.api.Transcriber
import com.example.speech.api.TranscriptionEvent
import com.example.speech.audio.WavReader
import com.example.speech.data.BundledModels
import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToLong

/**
 * Batch-расшифровка: диаризация pyannote + CAM++ и ASR GigaAM-v3 по сегментам.
 *
 * Жизненный цикл моделей:
 * - [load] и [transcribe] сериализованы одним [Mutex];
 * - [release] во время расшифровки не освобождает native-объекты, а
 *   откладывает освобождение до её конца (см. [ReleaseGate]);
 * - диаризаторы кешируются по числу кластеров: переключение «авто → 2 → 3»
 *   больше не перезагружает embedding-модель каждый раз.
 */
class SherpaTranscriber(private val context: Context) : Transcriber {

    private val mutex = Mutex()

    @Volatile private var recognizer: OfflineRecognizer? = null
    @Volatile private var loadedConfig: SpeechConfig? = null

    @Volatile private var segmentationPath: String? = null
    @Volatile private var embeddingPath: String? = null

    @Volatile private var diarizationProgressSink: ((Float) -> Unit)? = null

    /**
     * Кеш диаризаторов по числу кластеров. Два экземпляра покрывают основной
     * сценарий «авто + один фиксированный N»; каждый держит ~27 МБ модели.
     */
    private val diarizers = LruCache<Int, OfflineSpeakerDiarization>(
        maxEntries = DIARIZER_CACHE_SIZE,
        onEvict = { diarizer -> runCatching { diarizer.release() } },
    )

    private val releaseGate = ReleaseGate(
        releaseModels = { releaseLocked() },
        tryAcquire = { mutex.tryLock() },
        unlock = { mutex.unlock() },
    )

    override suspend fun load(config: SpeechConfig) = withContext(Dispatchers.IO) {
        mutex.lock()
        var loadSucceeded = false
        try {
            if (loadedConfig?.batchModelsMatch(config) == true &&
                recognizer != null &&
                diarizers.size > 0
            ) {
                // Модели те же: достаточно запомнить конфиг, чтобы новые
                // runtime-параметры (pruning и т. п.) применялись без
                // холодной перезагрузки весов. При успехе endLoad() отменит
                // отложенный release() — новая загрузка важнее старого запроса.
                loadedConfig = config
                loadSucceeded = true
                return@withContext
            }

            val appContext = context.applicationContext
            BundledModels.requirePresent(appContext)
            BundledModels.requireEmbedding(appContext, config.embeddingModel)
            BundledModels.requireAsr(appContext, config.asrModel)
            val assets = appContext.assets
            val embeddingAsset = BundledModels.embeddingFor(config.embeddingModel)

            releaseLocked()

            val newRecognizer = SherpaModelFactory.createRecognizer(
                assetManager = assets,
                config = config,
            )

            try {
                diarizers.getOrCreate(AUTO_CLUSTERS) {
                    createDiarization(
                        assetManager = assets,
                        config = config,
                        segmentationPath = BundledModels.SEGMENTATION,
                        embeddingPath = embeddingAsset,
                        numClusters = AUTO_CLUSTERS,
                    )
                }
            } catch (t: Throwable) {
                runCatching { newRecognizer.release() }
                throw t
            }

            recognizer = newRecognizer
            loadedConfig = config
            segmentationPath = BundledModels.SEGMENTATION
            embeddingPath = embeddingAsset
            loadSucceeded = true
        } finally {
            releaseGate.endLoad(cancelPending = loadSucceeded)
        }
    }

    override fun transcribe(wav: File, expectedSpeakers: Int?): Flow<TranscriptionEvent> =
        channelFlow {
            if (expectedSpeakers != null && expectedSpeakers !in MIN_SPEAKERS..MAX_SPEAKERS) {
                send(
                    TranscriptionEvent.Failed(
                        IllegalArgumentException(
                            "expectedSpeakers должен быть null (авто) или " +
                                "$MIN_SPEAKERS..$MAX_SPEAKERS, получено $expectedSpeakers",
                        ),
                    ),
                )
                return@channelFlow
            }

            mutex.lock()
            try {
                transcribeLocked(this, wav, expectedSpeakers)
            } finally {
                // release(), пришедший во время расшифровки, выполняется
                // здесь — после последнего native-вызова, а не под ним.
                // endSession() отпускает mutex под тем же handoff-локом,
                // поэтому запрос не может потеряться между проверкой и unlock().
                releaseGate.endSession()
            }
        }.flowOn(Dispatchers.IO)

    private suspend fun transcribeLocked(
        events: SendChannel<TranscriptionEvent>,
        wav: File,
        expectedSpeakers: Int?,
    ) {
        val config = loadedConfig
        val recognizer = recognizer
        if (config == null || recognizer == null) {
            events.send(
                TranscriptionEvent.Failed(
                    IllegalStateException(
                        "Модели не загружены. Вызовите load(SpeechConfig(...)) перед transcribe().",
                    ),
                ),
            )
            return
        }

        try {
            events.send(TranscriptionEvent.Progress(Stage.DIARIZATION, 0f))
            val samples = WavReader.readMono16k(wav)
            if (samples.isEmpty()) {
                throw IllegalArgumentException("В файле нет аудиоданных: ${wav.absolutePath}")
            }

            val totalDurationMs = samples.size * 1000L / SAMPLE_RATE
            val diarizedSegments = if (expectedSpeakers == 1) {
                listOf(
                    RawSegment(
                        startSec = 0f,
                        endSec = samples.size.toFloat() / SAMPLE_RATE,
                        speaker = 0,
                    ),
                )
            } else {
                val desiredClusters = expectedSpeakers ?: AUTO_CLUSTERS
                val diarizer = prepareDiarization(config, desiredClusters)
                runDiarization(diarizer, samples, events).ifEmpty {
                    listOf(
                        RawSegment(
                            startSec = 0f,
                            endSec = samples.size.toFloat() / SAMPLE_RATE,
                            speaker = 0,
                        ),
                    )
                }
            }

            events.send(TranscriptionEvent.Progress(Stage.DIARIZATION, 1f))

            val mergedSegments = mergeShortFragments(diarizedSegments)
            // Auto-режим не знает истинного числа спикеров, поэтому мелкие
            // кластеры от перекластеризации нужно убирать. В fixed-режиме
            // пользователь явно задал число — не трогаем его результат.
            val rawSegments = if (expectedSpeakers == null) {
                pruneTinyClusters(
                    segments = mergedSegments,
                    minSupportMs = config.clusterPruneMinMs.toLong(),
                    minFraction = config.clusterPruneMinFraction,
                )
            } else {
                mergedSegments
            }

            val speakerIdMap = HashMap<Int, Int>()
            val segments = rawSegments.map { raw ->
                val speaker = speakerIdMap.getOrPut(raw.speaker) { speakerIdMap.size }
                RawSegment(raw.startSec, raw.endSec, speaker)
            }

            events.send(TranscriptionEvent.Progress(Stage.RECOGNITION, 0f))

            val asr = AsrRunner(recognizer, SAMPLE_RATE)
            val result = ArrayList<Segment>(segments.size)
            for ((index, raw) in segments.withIndex()) {
                currentCoroutineContext().ensureActive()

                val startSample = (raw.startSec * SAMPLE_RATE).toInt().coerceIn(0, samples.size)
                val endSample = (raw.endSec * SAMPLE_RATE).toInt().coerceIn(0, samples.size)
                val startMs = (raw.startSec * 1000f).roundToLong().coerceIn(0L, totalDurationMs)
                val endMs = (raw.endSec * 1000f).roundToLong().coerceIn(0L, totalDurationMs)

                if (endSample > startSample && endMs - startMs >= MIN_SEGMENT_MS) {
                    val text = asr.transcribeRange(samples, startSample, endSample)
                    if (text.isNotBlank()) {
                        val segment = Segment(
                            speaker = raw.speaker,
                            startMs = startMs,
                            endMs = endMs,
                            text = text,
                        )
                        result += segment
                        events.send(TranscriptionEvent.SegmentReady(segment))
                    }
                }

                events.send(
                    TranscriptionEvent.Progress(
                        Stage.RECOGNITION,
                        (index + 1).toFloat() / segments.size,
                    ),
                )
            }

            events.send(TranscriptionEvent.Done(result))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            events.send(TranscriptionEvent.Failed(e))
        }
    }

    /**
     * Возвращает диаризатор для [desiredClusters], переиспользуя кеш.
     *
     * Модель привязана к числу кластеров, поэтому держать один экземпляр
     * недостаточно: чередование «2 → 3 → 2» каждый раз перезагружало 27 МБ.
     */
    private fun prepareDiarization(
        config: SpeechConfig,
        desiredClusters: Int,
    ): OfflineSpeakerDiarization {
        val segmentation = checkNotNull(segmentationPath) { "Модель сегментации не задана" }
        val embedding = checkNotNull(embeddingPath) { "Embedding-модель не задана" }

        return diarizers.getOrCreate(desiredClusters) {
            createDiarization(
                assetManager = context.applicationContext.assets,
                config = config,
                segmentationPath = segmentation,
                embeddingPath = embedding,
                numClusters = desiredClusters,
            )
        }
    }

    private suspend fun runDiarization(
        diarizer: OfflineSpeakerDiarization,
        samples: FloatArray,
        events: SendChannel<TranscriptionEvent>,
    ): List<RawSegment> = coroutineScope {
        val progress = Channel<Float>(Channel.CONFLATED)
        val progressJob = launch {
            for (fraction in progress) {
                events.send(TranscriptionEvent.Progress(Stage.DIARIZATION, fraction))
            }
        }

        diarizationProgressSink = { fraction -> progress.trySend(fraction) }
        try {
            diarizer.processWithCallback(
                samples = samples,
                callback = ::diarizationCallback,
                arg = 0L,
            ).map { RawSegment(it.start, it.end, it.speaker) }
        } finally {
            diarizationProgressSink = null
            progress.close()
            progressJob.join()
        }
    }

    private fun createDiarization(
        assetManager: AssetManager,
        config: SpeechConfig,
        segmentationPath: String,
        embeddingPath: String,
        numClusters: Int,
    ): OfflineSpeakerDiarization = OfflineSpeakerDiarization(
        assetManager = assetManager,
        config = OfflineSpeakerDiarizationConfig(
            segmentation = OfflineSpeakerSegmentationModelConfig(
                pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(model = segmentationPath),
                numThreads = config.numThreads,
                debug = false,
                provider = "cpu",
            ),
            embedding = SpeakerEmbeddingExtractorConfig(
                model = embeddingPath,
                numThreads = config.numThreads,
                debug = false,
                provider = "cpu",
            ),
            clustering = FastClusteringConfig(
                numClusters = numClusters,
                threshold = config.autoClusterThreshold,
            ),
            minDurationOn = MIN_DURATION_ON,
            minDurationOff = MIN_DURATION_OFF,
        ),
    )

    @Keep
    private fun diarizationCallback(
        processed: Int,
        total: Int,
        @Suppress("UNUSED_PARAMETER") arg: Long,
    ): Int {
        val fraction = if (total > 0) processed.toFloat() / total else 0f
        diarizationProgressSink?.invoke(fraction.coerceIn(0f, 1f))
        return 0
    }

    override fun release() {
        // Если идёт расшифровка, запрос откладывается до её конца. Логика
        // «запрос + tryLock + handoff» живёт в ReleaseGate.
        releaseGate.release()
    }

    private fun releaseLocked() {
        runCatching { recognizer?.release() }
        recognizer = null
        diarizers.clear()
        loadedConfig = null
        segmentationPath = null
        embeddingPath = null
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val MIN_SPEAKERS = 1
        const val MAX_SPEAKERS = 20

        const val AUTO_CLUSTERS = -1
        const val MIN_DURATION_ON = 0.3f
        const val MIN_DURATION_OFF = 0.5f

        const val MIN_SEGMENT_MS = 200L

        /** Сколько вариантов диаризатора держать одновременно. */
        const val DIARIZER_CACHE_SIZE = 2
    }
}
