package com.example.speech.impl

import android.content.Context
import com.example.speech.api.Segment
import com.example.speech.api.SpeechConfig
import com.example.speech.api.StreamingEvent
import com.example.speech.api.StreamingTranscriber
import com.example.speech.audio.OnlineSpeakerClusterer
import com.example.speech.audio.SherpaVadEngine
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

class SherpaStreamingTranscriber(private val context: Context) : StreamingTranscriber {

    private val loadMutex = Mutex()
    private val sessionMutex = Mutex()

    @Volatile private var recognizer: OfflineRecognizer? = null
    @Volatile private var vad: VadSegmenter? = null
    @Volatile private var embeddingExtractor: SpeakerEmbeddingExtractor? = null
    @Volatile private var loadedConfig: SpeechConfig? = null

    override suspend fun load(config: SpeechConfig) = withContext(Dispatchers.IO) {
        require(config.numThreads >= 1) { "numThreads должен быть >= 1" }

        loadMutex.withLock {
            if (loadedConfig == config &&
                recognizer != null &&
                vad != null &&
                embeddingExtractor != null
            ) {
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
        }
    }

    override fun transcribeStream(audio: Flow<FloatArray>): Flow<StreamingEvent> = channelFlow {
        sessionMutex.withLock {
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
                return@withLock
            }

            vad.reset()
            val clusterer = OnlineSpeakerClusterer(
                threshold = config.onlineClusterThreshold,
                maxSpeakers = config.maxOnlineSpeakers,
            )
            val queue = Channel<SpeechChunk>(capacity = ASR_QUEUE_CAPACITY)
            val produced = ArrayList<Segment>()
            var workerFailure: Throwable? = null

            val worker = launch(Dispatchers.Default) {
                try {
                    val asr = AsrRunner(recognizer, VadSegmenter.SAMPLE_RATE)
                    for (chunk in queue) {
                        currentCoroutineContext().ensureActive()

                        val text = asr.transcribe(chunk.samples)
                        if (text.isBlank()) continue

                        val assignment = assignSpeaker(embedder, clusterer, chunk.samples)
                        val startMs = chunk.startSample * 1000L / VadSegmenter.SAMPLE_RATE
                        val endMs = (chunk.startSample + chunk.samples.size) * 1000L /
                            VadSegmenter.SAMPLE_RATE
                        val segment = Segment(
                            speaker = assignment.speaker,
                            startMs = startMs,
                            endMs = endMs,
                            text = text,
                        )
                        produced += segment
                        send(StreamingEvent.Utterance(segment))
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
                    send(StreamingEvent.Done(produced.toList()))
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
        }
    }.flowOn(Dispatchers.Default)

    override fun release() {
        releaseLocked()
    }

    private fun assignSpeaker(
        extractor: SpeakerEmbeddingExtractor,
        clusterer: OnlineSpeakerClusterer,
        samples: FloatArray,
    ): OnlineSpeakerClusterer.Assignment {
        if (samples.size < MIN_EMBEDDING_SAMPLES) {
            return fallbackAssignment(clusterer)
        }

        return try {
            val embedding = extractEmbedding(extractor, samples)
            if (embedding == null) {
                fallbackAssignment(clusterer)
            } else {
                clusterer.assign(embedding)
            }
        } catch (_: Throwable) {
            fallbackAssignment(clusterer)
        }
    }

    private fun fallbackAssignment(
        clusterer: OnlineSpeakerClusterer,
    ): OnlineSpeakerClusterer.Assignment {
        val speaker = clusterer.lastSpeaker.coerceAtLeast(0)
        return OnlineSpeakerClusterer.Assignment(
            speaker = speaker,
            similarity = 0f,
            isNewSpeaker = false,
        )
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

    private companion object {
        const val ASR_QUEUE_CAPACITY = 8

        const val MIN_EMBEDDING_MS = 500
        const val MIN_EMBEDDING_SAMPLES = VadSegmenter.SAMPLE_RATE * MIN_EMBEDDING_MS / 1000
    }
}
