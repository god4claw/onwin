package com.example.speech.impl

import android.content.Context
import android.util.Log
import com.example.speech.api.Segment
import com.example.speech.api.SpeechConfig
import com.example.speech.api.StreamingEvent
import com.example.speech.api.StreamingTranscriber
import com.example.speech.audio.AutomaticGainControl
import com.example.speech.audio.OnlineSpeakerClusterer
import com.example.speech.audio.SampleHistory
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
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Псевдостриминг v2: Silero VAD режет поток на реплики, после чего каждая
 * реплика распознаётся offline-моделью целиком.
 *
 * Это не настоящий streaming: промежуточных гипотез здесь нет, текст приходит
 * только после конца реплики. Настоящего streaming (online-модель,
 * `OnlineStream.acceptWaveform` + `decode`) в модуле пока нет.
 *
 * Диагностика: `adb shell setprop log.tag.Speech DEBUG` включает подробный
 * logcat (границы VAD, разрезы по смене спикера, время ASR и embedding,
 * назначение кластера). Пишутся только тайминги и номера спикеров, без текста.
 *
 * Устройство:
 * - АРУ ([AutomaticGainControl], `SpeechConfig.agc`) выравнивает громкость
 *   только для VAD, чтобы он слышал и дальнего собеседника. ASR и embedding
 *   получают исходный сигнал того же отрезка (из [SampleHistory]): усиление,
 *   меняющееся внутри реплики, и поднятый фон портили embedding и сливали
 *   спикеров;
 * - VAD-гейт принимает аудио и отдаёт готовые чанки;
 * - [StreamingChunkProcessor] режет чанк по смене говорящего, распознаёт куски
 *   и назначает спикера;
 * - готовые реплики уходят в `Utterance` с предварительной (онлайн) меткой
 *   спикера; в конце — `Done`, где метки пересчитаны [SpeakerReclusterer]
 *   по всем embedding-ам сессии.
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

    /**
     * Отладка/бенчмарк: в конце сессии получает все реплики с embedding
     * (до финальной обработки меток). В приложении не используется.
     */
    @Volatile internal var debugRecordsSink: ((List<SegmentRecord>) -> Unit)? = null

    private val releaseGate = ReleaseGate(
        releaseModels = { releaseLocked() },
        tryAcquire = { sessionMutex.tryLock() },
        unlock = { sessionMutex.unlock() },
    )

    override suspend fun load(config: SpeechConfig) = withContext(Dispatchers.IO) {
        // Один mutex и на загрузку, и на сессию: иначе load() мог бы выгрузить
        // модели под работающим стримом. Пока стрим идёт, load() честно ждёт.
        // endLoad() отпускает mutex под handoff-локом и при успешной загрузке
        // отменяет отложенный release(): новая загрузка важнее старого release().
        sessionMutex.lock()
        var loadSucceeded = false
        try {
            if (loadedConfig?.streamingModelsMatch(config) == true &&
                recognizer != null &&
                vad != null &&
                embeddingExtractor != null
            ) {
                // Модели те же: обновляем конфиг, чтобы новые runtime-параметры
                // применились без перезагрузки 350 МБ весов.
                loadedConfig = config
                loadSucceeded = true
                return@withContext
            }

            val appContext = context.applicationContext
            BundledModels.requirePresent(appContext)
            BundledModels.requireEmbedding(appContext, config.embeddingModel)
            BundledModels.requireAsr(appContext, config.asrModel)
            val assets = appContext.assets

            releaseLocked()

            val newRecognizer = SherpaModelFactory.createRecognizer(
                assetManager = assets,
                config = config,
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
            loadSucceeded = true
        } finally {
            releaseGate.endLoad(cancelPending = loadSucceeded)
        }
    }

    override fun transcribeStream(audio: Flow<FloatArray>): Flow<StreamingEvent> =
        transcribeStream(audio, expectedSpeakers = null)

    /**
     * То же с подсказкой числа участников: финальная перекластеризация в `Done`
     * сводит реплики ровно к [expectedSpeakers] спикерам. Нужна обработке
     * файлов ([SherpaVadTranscriber]); онлайн-метки в `Utterance` не меняются.
     */
    internal fun transcribeStream(
        audio: Flow<FloatArray>,
        expectedSpeakers: Int?,
    ): Flow<StreamingEvent> = channelFlow {
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
                    hopMs = config.speakerChangeHopMs,
                    threshold = config.speakerChangeThreshold,
                    minSegmentMs = config.speakerChangeMinSegmentMs,
                ),
            )
            val agc = if (config.agc.enabled) {
                AutomaticGainControl(
                    targetDbfs = config.agc.targetDbfs,
                    maxGainDb = config.agc.maxGainDb,
                )
            } else {
                null
            }
            // Исходный сигнал для ASR/embedding, когда VAD работает по выровненному.
            val history = agc?.let { SampleHistory(HISTORY_SECONDS * VadSegmenter.SAMPLE_RATE) }
            val trace = debugTrace()
            trace?.invoke(
                "stream start threads=${config.numThreads} vad=${config.vad} agc=${config.agc} " +
                    "online=${config.onlineClusterThreshold} change=${config.speakerChangeThreshold} " +
                    "prune=${config.clusterPruneMinMs}ms/${config.clusterPruneMinFraction}",
            )
            val streamStartedAt = System.nanoTime()

            val asr = AsrRunner(recognizer, VadSegmenter.SAMPLE_RATE)
            val processor = StreamingChunkProcessor(
                transcribeWords = { samples -> asr.transcribeWords(samples) },
                embeddingOf = { samples -> extractEmbedding(embedder, samples) },
                clusterer = clusterer,
                splitter = splitter,
                trace = trace,
            )

            val queue = Channel<SpeechChunk>(capacity = ASR_QUEUE_CAPACITY)
            // Сколько VAD-чанков ждёт ASR. Рост до ASR_QUEUE_CAPACITY значит, что
            // распознавание не успевает за речью и скоро начнёт тормозить вход.
            val pending = AtomicInteger(0)
            suspend fun enqueue(vadChunk: SpeechChunk) {
                val original = history?.slice(vadChunk.startSample, vadChunk.samples.size)
                if (history != null && original == null) {
                    trace?.invoke("vad chunk older than history, ASR gets the AGC signal")
                }
                val chunk = original?.let { vadChunk.copy(samples = it) } ?: vadChunk
                val waiting = pending.incrementAndGet()
                trace?.invoke(
                    "vad chunk start=${chunk.startSample * 1000L / VadSegmenter.SAMPLE_RATE}ms " +
                        "dur=${chunk.samples.size * 1000L / VadSegmenter.SAMPLE_RATE}ms pending=$waiting" +
                        (agc?.let { " gain=%.1fdB".format(Locale.US, it.currentGainDb) } ?: ""),
                )
                queue.send(chunk)
            }
            val produced = ArrayList<Segment>()
            var workerFailure: Throwable? = null

            val worker = launch(Dispatchers.Default) {
                try {
                    var chunkIndex = 0
                    for (chunk in queue) {
                        pending.decrementAndGet()
                        currentCoroutineContext().ensureActive()

                        // VAD-чанк может содержать несколько говорящих: процессор
                        // распознаёт его целиком и делит текст по смене голоса на
                        // границах слов, у каждой реплики — свой embedding и спикер.
                        for (segment in processor.process(chunk, chunkIndex++)) {
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
                    history?.append(samples)
                    val update = vad.accept(agc?.process(samples) ?: samples)
                    update.speechStartedAtSample?.let { sample ->
                        send(
                            StreamingEvent.SpeechStarted(
                                sample * 1000L / VadSegmenter.SAMPLE_RATE,
                            ),
                        )
                    }
                    for (chunk in update.chunks) {
                        chunk.trimmedTo(vad.receivedSamples)?.let { enqueue(it) }
                    }
                }

                for (chunk in vad.flush()) {
                    chunk.trimmedTo(vad.receivedSamples)?.let { enqueue(it) }
                }
                queue.close()
                worker.join()

                val failure = workerFailure
                if (failure != null) {
                    trace?.invoke("stream failed: ${failure.javaClass.simpleName}")
                    send(StreamingEvent.Failed(failure))
                } else {
                    val records = processor.records.toList()
                    debugRecordsSink?.invoke(records)
                    // Онлайн-метки — предварительные: в Done спикеры пересчитываются
                    // по всем embedding-ам сессии разом.
                    val relabeled = SpeakerReclusterer(
                        mergeThreshold = config.speakerReclusterThreshold,
                        minSpeakerShare = config.speakerMinShare,
                    ).relabel(records, expectedSpeakers)
                    // Соседние реплики одного чанка с одним финальным спикером —
                    // ложный разрез по смене голоса: склеиваем обратно.
                    val final = mergeSameSpeakerNeighbors(relabeled, records.map { it.chunkIndex })
                    trace?.invoke(
                        "stream done segments=${final.size} " +
                            "speakers online=${produced.map { it.speaker }.distinct().size}" +
                            " final=${final.map { it.speaker }.distinct().size} " +
                            "audio=${vad.receivedSamples * 1000L / VadSegmenter.SAMPLE_RATE}ms " +
                            (agc?.let { "agcMax=%.1fdB ".format(Locale.US, it.maxAppliedGainDb) } ?: "") +
                            "took=${(System.nanoTime() - streamStartedAt) / 1_000_000}ms",
                    )
                    send(StreamingEvent.Done(final))
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
            // операциями, а не под работающим native-вызовом. endSession()
            // отпускает mutex под тем же handoff-локом, поэтому запрос не
            // может потеряться между проверкой и unlock().
            releaseGate.endSession()
        }
    }.flowOn(Dispatchers.Default)

    override fun release() {
        // Если идёт стрим, запрос откладывается до его конца. Логика
        // «запрос + tryLock + handoff» живёт в ReleaseGate.
        releaseGate.release()
    }

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
    }

    /**
     * Логгер для одной сессии или `null`, если debug-лог выключен. Проверка
     * раз на сессию: `setprop` между сессиями подхватится без перезапуска.
     */
    private fun debugTrace(): ((String) -> Unit)? =
        if (Log.isLoggable(LOG_TAG, Log.DEBUG)) {
            { message -> Log.d(LOG_TAG, message) }
        } else {
            null
        }

    private companion object {
        const val LOG_TAG = "Speech"
        const val ASR_QUEUE_CAPACITY = 8

        /** Сколько исходного аудио держать для ASR при включённой АРУ: ~5,8 МБ. */
        const val HISTORY_SECONDS = 90

        const val MIN_NEW_SPEAKER_MS = 2_500
        const val MIN_NEW_SPEAKER_SAMPLES = VadSegmenter.SAMPLE_RATE * MIN_NEW_SPEAKER_MS / 1000
    }
}
