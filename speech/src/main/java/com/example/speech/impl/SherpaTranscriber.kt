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
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToLong

class SherpaTranscriber(private val context: Context) : Transcriber {

    private val mutex = Mutex()

    @Volatile private var recognizer: OfflineRecognizer? = null
    @Volatile private var diarization: OfflineSpeakerDiarization? = null
    @Volatile private var loadedConfig: SpeechConfig? = null
    @Volatile private var loadedClusters: Int = Int.MIN_VALUE

    @Volatile private var segmentationPath: String? = null
    @Volatile private var embeddingPath: String? = null

    @Volatile private var diarizationProgressSink: ((Float) -> Unit)? = null

    override suspend fun load(config: SpeechConfig) = withContext(Dispatchers.IO) {
        require(config.numThreads >= 1) { "numThreads должен быть >= 1" }

        mutex.withLock {
            if (loadedConfig == config && recognizer != null && diarization != null) {
                return@withLock
            }

            val appContext = context.applicationContext
            BundledModels.requirePresent(appContext)
            val assets = appContext.assets
            val embeddingAsset = BundledModels.embeddingFor(config.embeddingModel)

            releaseLocked()

            val newRecognizer = SherpaModelFactory.createRecognizer(
                assetManager = assets,
                config = config,
                asrModelPath = BundledModels.ASR,
                tokensPath = BundledModels.TOKENS,
            )

            val newDiarization = try {
                createDiarization(
                    assetManager = assets,
                    config = config,
                    segmentationPath = BundledModels.SEGMENTATION,
                    embeddingPath = embeddingAsset,
                    numClusters = AUTO_CLUSTERS,
                )
            } catch (t: Throwable) {
                runCatching { newRecognizer.release() }
                throw t
            }

            recognizer = newRecognizer
            diarization = newDiarization
            loadedConfig = config
            loadedClusters = AUTO_CLUSTERS
            segmentationPath = BundledModels.SEGMENTATION
            embeddingPath = embeddingAsset
        }
    }

    override fun transcribe(wav: File): Flow<TranscriptionEvent> = transcribe(wav, null)

    override fun transcribe(wav: File, expectedSpeakers: Int?): Flow<TranscriptionEvent> = channelFlow {
        if (expectedSpeakers != null && expectedSpeakers !in MIN_SPEAKERS..MAX_SPEAKERS) {
            send(
                TranscriptionEvent.Failed(
                    IllegalArgumentException(
                        "expectedSpeakers должен быть null (авто) или $MIN_SPEAKERS..$MAX_SPEAKERS, " +
                            "получено $expectedSpeakers",
                    ),
                ),
            )
            return@channelFlow
        }

        mutex.withLock {
            transcribeLocked(this, wav, expectedSpeakers)
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun transcribeLocked(
        events: SendChannel<TranscriptionEvent>,
        wav: File,
        expectedSpeakers: Int?,
    ) {
        val config = loadedConfig
        val recognizer = recognizer
        if (config == null || recognizer == null || diarization == null) {
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

            val rawSegments = mergeShortFragments(diarizedSegments)

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
        } catch (t: Throwable) {
            events.send(TranscriptionEvent.Failed(t))
        }
    }

    private fun mergeShortFragments(segments: List<RawSegment>): List<RawSegment> {
        if (segments.size < 2) return segments
        val sorted = segments.sortedBy { it.startSec }
        val result = ArrayList<RawSegment>(sorted.size)
        var index = 0

        while (index < sorted.size) {
            val current = sorted[index]
            val currentDuration = current.endSec - current.startSec
            val previous = result.lastOrNull()
            val previousDuration = previous?.let { it.endSec - it.startSec } ?: 0f

            if (previous != null &&
                currentDuration < SHORT_FRAGMENT_SEC &&
                current.startSec - previous.endSec <= MAX_FRAGMENT_GAP_SEC &&
                previousDuration > currentDuration * 2f
            ) {
                result[result.lastIndex] = previous.copy(endSec = current.endSec)
                index += 1
                continue
            }

            val next = sorted.getOrNull(index + 1)
            if (next != null &&
                currentDuration < SHORT_FRAGMENT_SEC &&
                next.startSec - current.endSec <= MAX_FRAGMENT_GAP_SEC &&
                (next.endSec - next.startSec) > currentDuration * 2f
            ) {
                result += next.copy(startSec = current.startSec)
                index += 2
                continue
            }

            result += current
            index += 1
        }

        return result
    }

    private fun prepareDiarization(
        config: SpeechConfig,
        desiredClusters: Int,
    ): OfflineSpeakerDiarization {
        if (desiredClusters == loadedClusters) {
            return checkNotNull(diarization) { "Диаризатор не инициализирован" }
        }

        val segmentation = checkNotNull(segmentationPath) { "Модель сегментации не задана" }
        val embedding = checkNotNull(embeddingPath) { "Embedding-модель не задана" }

        runCatching { diarization?.release() }
        diarization = null

        val recreated = createDiarization(
            assetManager = context.applicationContext.assets,
            config = config,
            segmentationPath = segmentation,
            embeddingPath = embedding,
            numClusters = desiredClusters,
        )
        diarization = recreated
        loadedClusters = desiredClusters
        return recreated
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
        releaseLocked()
    }

    private fun releaseLocked() {
        runCatching { recognizer?.release() }
        runCatching { diarization?.release() }
        recognizer = null
        diarization = null
        loadedConfig = null
        loadedClusters = Int.MIN_VALUE
        segmentationPath = null
        embeddingPath = null
    }

    private data class RawSegment(
        val startSec: Float,
        val endSec: Float,
        val speaker: Int,
    )

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val MIN_SPEAKERS = 1
        const val MAX_SPEAKERS = 20

        const val AUTO_CLUSTERS = -1
        const val MIN_DURATION_ON = 0.3f
        const val MIN_DURATION_OFF = 0.5f

        const val MIN_SEGMENT_MS = 200L

        const val SHORT_FRAGMENT_SEC = 0.7f
        const val MAX_FRAGMENT_GAP_SEC = 0.25f
    }
}
