package com.example.speech.impl

import android.content.Context
import com.example.speech.api.Segment
import com.example.speech.api.SpeechConfig
import com.example.speech.api.StreamingEvent
import com.example.speech.api.StreamingTranscriber
import com.example.speech.audio.OnlineSpeakerClusterer
import com.example.speech.audio.SherpaVadEngine
import com.example.speech.audio.SpeakerChangeConfig
import com.example.speech.audio.SpeakerChangeSplitter
import com.example.speech.audio.SpeechChunk
import com.example.speech.audio.VadSegmenter
import com.example.speech.data.BundledModels
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Псевдостриминг v2: Silero VAD режет поток на реплики, после чего каждая
 * реплика распознаётся offline-моделью целиком.
 *
 * Это не настоящий streaming: промежуточных гипотез (`Partial`) здесь нет,
 * текст приходит только после конца реплики. Настоящий streaming появился в v3
 * (online-модель, `OnlineStream.acceptWaveform` + `decode`).
 *
 * Устройство:
 * - VAD-гейт принимает аудио и отдаёт готовые чанки;
 * - [StreamingChunkProcessor] режет чанк по смене говорящего, распознаёт куски
 *   и назначает спикера;
 * - готовые реплики уходят в `Utterance`, в конце — `Done`.
 *
 * Жизненный цикл моделей:
 * - [load] и [transcribeStream] сериализованы одним mutex;
 * - второй стрим не ждёт молча, а получает `Failed(«занято»)`;
 * - [release] во время стрима откладывается до его конца.
 */
class SherpaStreamingTranscriber(private val context: Context) : StreamingTranscriber {

    private val sessionMutex = Mutex()

    @Volatile private var recognizer: OfflineRecognizer? = null
    @Volatile private var vad: VadSegmenter? = null
    @Volatile private var embeddingExtractor: SpeakerEmbeddingExtractor? = null
    @Volatile private var loadedConfig: SpeechConfig? = null

    private val releaseGate = ReleaseGate { releaseLocked() }

    override suspend fun load(config: SpeechConfig) = withContext(Dispatchers.IO) {
        // Один mutex и на загрузку, и на сессию: иначе load() мог бы выгрузить
        // модели под работающим стримом. Пока стрим идёт, load() честно ждёт.
        sessionMutex.withLock {
            if (loadedConfig?.streamingModelsMatch(config) == true &&
                recognizer != null &&
                vad != null &&
                embeddingExtractor != null
            ) {
                // Модели те же: обновляем конфиг, чтобы новые runtime-параметры
                // применились без перезагрузки 350 МБ весов.
                loadedConfig = config
                releaseGate.cancel()
                return@withLock
            }

            val appContext = context.applicationContext
            BundledModels.requirePresent(appContext)
            val assets = appContext.assets

            releaseLocked()

            val newRecognizer = SherpaModelFactory.createRecognizer(
                assetManager = assets,
                config = config,
                asrModelPath = BundledModels.ASR,
                tokensPath = BundledModels.TOKENS,
            )

            val newVad = try {
                VadSegmenter(
                    engine = SherpaVadEngine(
                        assetManager = assets,
                        modelPath = BundledModels.VAD,
                        config = config.vad,
                    ),
                )
            } catch (t: Throwable) {
                runCatching { newRecognizer.release() }
                throw t
            }

            val newEmbedder = try {
                SpeakerEmbeddingExtractor(
                    assetManager = assets,
                    config = SpeakerEmbeddingExtractorConfig(
                        model = BundledModels.embeddingFor(config.embeddingModel),
                        numThreads = config.numThreads,
                        debug = false,
                        provider = "cpu",
                    ),
                )
            } catch (t: Throwable) {
                runCatching { newVad.release() }
                runCatching { newRecognizer.release() }
                throw t
            }

            recognizer = newRecognizer
            vad = newVad
            embeddingExtractor = newEmbedder
            loadedConfig = config
            releaseGate.cancel()
        }
    }

    override fun transcribeStream(audio: Flow<FloatArray>): Flow<StreamingEvent> = channelFlow {
        // Второй стрим не ждёт первый молча, а честно сообщает, что занято:
        // у микрофона ожидание может длиться бесконечно.
        if (!sessionMutex.tryLock()) {
            send(
                StreamingEvent.Failed(
                    IllegalStateException("Уже идёт другая streaming-сессия. Дождитесь её конца."),
                ),
            )
            return@channelFlow
        }

        try {
            val config = loadedConfig
            val recognizer = recognizer
            val vad = vad
            val embedder = embeddingExtractor
            if (config == null || recognizer == null || vad == null || embedder == null) {
                send(
                    StreamingEvent.Failed(
                        IllegalStateException(
                            "Модели не загружены. Вызовите load(SpeechConfig(...)) " +
                                "перед transcribeStream().",
                        ),
                    ),
                )
                return@channelFlow
            }

            vad.reset()
            val clusterer = OnlineSpeakerClusterer(
                threshold = config.onlineClusterThreshold,
                maxSpeakers = config.maxOnlineSpeakers,
                minNewSpeakerSamples = MIN_NEW_SPEAKER_SAMPLES,
            )
            val splitter = SpeakerChangeSplitter(
                SpeakerChangeConfig(
                    windowMs = config.speakerChangeWindowMs,
                    threshold = config.speakerChangeThreshold,
                    minSegmentMs = config.speakerChangeMinSegmentMs,
                ),
            )
            val asr = AsrRunner(recognizer, VadSegmenter.SAMPLE_RATE)
            val processor = StreamingChunkProcessor(
                transcribe = { samples -> asr.transcribe(samples) },
                embeddingOf = { samples -> extractEmbedding(embedder, samples) },
                clusterer = clusterer,
                splitter = splitter,
            )

            val queue = Channel<SpeechChunk>(capacity = ASR_QUEUE_CAPACITY)
            val produced = ArrayList<Segment>()
            var workerFailure: Throwable? = null

            val worker = launch(Dispatchers.Default) {
                try {
                    for (chunk in queue) {
                        currentCoroutineContext().ensureActive()

                        // VAD-чанк может содержать несколько говорящих. Режем его
                        // по смене embedding-а до ASR и кластеризации, иначе один
                        // вектор на смешанный кусок ломает всю онлайн-диаризацию.
                        for (piece in processor.split(chunk)) {
                            currentCoroutineContext().ensureActive()
                            val segment = processor.segment(piece) ?: continue
                            produced += segment
                            send(StreamingEvent.Utterance(segment))
                        }
                    }
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    workerFailure = t
                    queue.close()
                }
            }

            try {
                audio.collect { samples ->
                    val update = vad.accept(samples)
                    update.speechStartedAtSample?.let { sample ->
                        send(
                            StreamingEvent.SpeechStarted(
                                sample * 1000L / VadSegmenter.SAMPLE_RATE,
                            ),
                        )
                    }
                    for (chunk in update.chunks) {
                        chunk.trimmedTo(vad.receivedSamples)?.let { queue.send(it) }
                    }
                }

                for (chunk in vad.flush()) {
                    chunk.trimmedTo(vad.receivedSamples)?.let { queue.send(it) }
                }
                queue.close()
                worker.join()

                val failure = workerFailure
                if (failure != null) {
                    send(StreamingEvent.Failed(failure))
                } else {
                    send(
                        StreamingEvent.Done(
                            pruneStreamingClusters(produced, config),
                        ),
                    )
                }
            } catch (c: CancellationException) {
                queue.cancel()
                throw c
            } catch (t: Throwable) {
                queue.cancel()
                runCatching { worker.join() }
                val cause = workerFailure ?: t
                if (cause is CancellationException) throw cause
                send(StreamingEvent.Failed(cause))
            } finally {
                withContext(NonCancellable) { runCatching { worker.join() } }
                runCatching { vad.reset() }
            }
        } finally {
            // release(), пришедший во время стрима, выполняется здесь — между
            // операциями, а не под работающим native-вызовом.
            runCatching { releaseGate.releaseIfRequested() }
            sessionMutex.unlock()
        }
    }.flowOn(Dispatchers.Default)

    override fun release() {
        if (!sessionMutex.tryLock()) {
            // Стрим идёт: освобождать native-объекты нельзя. Запоминаем запрос,
            // стрим выполнит его в своём finally.
            releaseGate.request()
            return
        }
        try {
            releaseLocked()
        } finally {
            sessionMutex.unlock()
        }
    }

    /**
     * Убирает мелкие кластеры, накопившиеся за сессию. Пока сессия идёт,
     * [OnlineSpeakerClusterer] старается их не создавать, но финальная
     * постобработка нужна как страховка: `Done` должен отдавать столько
     * спикеров, сколько реально слышно.
     */
    private fun pruneStreamingClusters(
        segments: List<Segment>,
        config: SpeechConfig,
    ): List<Segment> = pruneTinyClustersOf(
        segments = segments,
        startMs = { it.startMs },
        endMs = { it.endMs },
        speaker = { it.speaker },
        withSpeaker = { segment, speaker -> segment.copy(speaker = speaker) },
        minSupportMs = config.clusterPruneMinMs.toLong(),
        minFraction = config.clusterPruneMinFraction,
    )

    private fun extractEmbedding(
        extractor: SpeakerEmbeddingExtractor,
        samples: FloatArray,
    ): FloatArray? {
        val stream = extractor.createStream()
        return try {
            stream.acceptWaveform(samples, VadSegmenter.SAMPLE_RATE)
            stream.inputFinished()
            if (!extractor.isReady(stream)) {
                null
            } else {
                extractor.compute(stream).takeIf { it.isNotEmpty() }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } finally {
            stream.release()
        }
    }

    private fun releaseLocked() {
        runCatching { embeddingExtractor?.release() }
        runCatching { vad?.release() }
        runCatching { recognizer?.release() }
        embeddingExtractor = null
        vad = null
        recognizer = null
        loadedConfig = null
        releaseGate.cancel()
    }

    private companion object {
        const val ASR_QUEUE_CAPACITY = 8

        const val MIN_NEW_SPEAKER_MS = 2_500
        const val MIN_NEW_SPEAKER_SAMPLES = VadSegmenter.SAMPLE_RATE * MIN_NEW_SPEAKER_MS / 1000
    }
}
