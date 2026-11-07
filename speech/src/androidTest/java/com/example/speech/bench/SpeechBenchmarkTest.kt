package com.example.speech.bench

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.speech.api.AgcConfig
import com.example.speech.api.Segment
import com.example.speech.api.SpeechConfig
import com.example.speech.api.Stage
import com.example.speech.api.StreamingEvent
import com.example.speech.api.TranscriptionEvent
import com.example.speech.api.VadConfig
import com.example.speech.audio.WavReader
import com.example.speech.impl.SherpaStreamingTranscriber
import com.example.speech.impl.SherpaTranscriber
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray
import kotlin.math.roundToLong

@RunWith(AndroidJUnit4::class)
class SpeechBenchmarkTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val args = InstrumentationRegistry.getArguments()

    @Test
    fun benchmark(): Unit = runBlocking {
        val wav = File(requiredArg("wav"))
        require(wav.isFile) { "WAV не найден на устройстве: ${wav.absolutePath}" }

        val mode = (args.getString("mode") ?: "batch").lowercase(Locale.US)
        require(mode in MODES) {
            "mode должен быть одним из ${MODES.joinToString()}, получено: $mode"
        }

        val threads = positiveInt("threads", 4)
        val runs = positiveInt("runs", 3)
        val chunkMs = positiveInt("chunk_ms", 100)
        val defaults = SpeechConfig()
        val autoThreshold = threshold("auto_threshold", defaults.autoClusterThreshold)
        val onlineThreshold = threshold("online_threshold", defaults.onlineClusterThreshold)
        val pruneMinMs = nonNegativeInt("prune_min_ms", defaults.clusterPruneMinMs)
        val pruneMinFraction = threshold("prune_min_fraction", defaults.clusterPruneMinFraction)
        val speakerChangeThreshold = threshold(
            "speaker_change_threshold",
            defaults.speakerChangeThreshold,
        )
        // 0 — подавать аудио так быстро, как примет транскрайбер (пропускная
        // способность); 1 — в реальном темпе, как живой микрофон (задержки).
        val pace = nonNegativeFloat("pace", 0f)
        val vad = VadConfig(
            threshold = threshold("vad_threshold", defaults.vad.threshold),
            minSilenceMs = nonNegativeInt("vad_min_silence_ms", defaults.vad.minSilenceMs),
            minSpeechMs = nonNegativeInt("vad_min_speech_ms", defaults.vad.minSpeechMs),
            maxSpeechMs = positiveInt("vad_max_speech_ms", defaults.vad.maxSpeechMs),
        )
        // Отладка: писать embedding каждой реплики в JSON (для подбора кластеризации на хосте).
        val dumpEmbeddings = onOff("dump_embeddings", false)
        val reclusterThreshold = threshold("recluster_threshold", defaults.speakerReclusterThreshold)
        val agc = AgcConfig(
            enabled = onOff("agc", defaults.agc.enabled),
            targetDbfs = floatArg("agc_target_db", defaults.agc.targetDbfs),
            maxGainDb = floatArg("agc_max_gain_db", defaults.agc.maxGainDb),
        )

        val speakersArg = args.getString("speakers")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: "auto"
        val expectedSpeakers = if (speakersArg.equals("auto", ignoreCase = true)) {
            null
        } else {
            speakersArg.toIntOrNull()?.also {
                require(it >= 1) { "speakers должен быть >= 1, получено: $it" }
            } ?: error("speakers должен быть auto или целым числом, получено: $speakersArg")
        }

        val label = args.getString("label")?.takeIf { it.isNotBlank() }
            ?: wav.nameWithoutExtension

        val wavInfo = readWavInfo(wav)
        val audioMs = wavInfo.durationMs
        val streamingSamples = if (mode == "streaming" || mode == "both") {
            WavReader.readMono16k(wav)
        } else {
            null
        }

        val config = BenchmarkConfig(
            threads = threads,
            runs = runs,
            speakers = speakersArg,
            chunkMs = chunkMs,
            autoClusterThreshold = autoThreshold,
            onlineClusterThreshold = onlineThreshold,
            clusterPruneMinMs = pruneMinMs,
            clusterPruneMinFraction = pruneMinFraction,
            speakerChangeThreshold = speakerChangeThreshold,
            pace = pace,
            vad = vad,
            agc = agc,
            reclusterThreshold = reclusterThreshold,
        )

        val batch = if (mode == "batch" || mode == "both") {
            runBatch(
                wav = wav,
                audioMs = audioMs,
                threads = threads,
                runs = runs,
                expectedSpeakers = expectedSpeakers,
                autoClusterThreshold = autoThreshold,
                pruneMinMs = pruneMinMs,
                pruneMinFraction = pruneMinFraction,
            )
        } else {
            null
        }
        val streaming = if (streamingSamples != null) {
            runStreaming(
                samples = streamingSamples,
                audioMs = audioMs,
                threads = threads,
                runs = runs,
                chunkMs = chunkMs,
                onlineClusterThreshold = onlineThreshold,
                pruneMinMs = pruneMinMs,
                pruneMinFraction = pruneMinFraction,
                speakerChangeThreshold = speakerChangeThreshold,
                vad = vad,
                agc = agc,
                pace = pace,
                dumpEmbeddings = dumpEmbeddings,
                reclusterThreshold = reclusterThreshold,
            )
        } else {
            null
        }

        val report = BenchmarkReport(
            generatedAt = isoNow(),
            label = label,
            mode = mode,
            audio = BenchmarkAudio(
                path = wav.absolutePath,
                durationMs = audioMs,
                samples = streamingSamples?.size ?: wavInfo.samples,
            ),
            config = config,
            device = readBenchmarkDevice(context),
            batch = batch,
            streaming = streaming,
        )

        val outFile = outputFile()
        val json = report.toJson()
        val table = report.formatTable()
        outFile.writeText(json)

        val tableFile = File(outFile.parentFile, outFile.nameWithoutExtension + ".txt")
        tableFile.writeText(table)
        writeLatest(outFile, json, table)
    }

    private suspend fun runBatch(
        wav: File,
        audioMs: Long,
        threads: Int,
        runs: Int,
        expectedSpeakers: Int?,
        autoClusterThreshold: Float,
        pruneMinMs: Int,
        pruneMinFraction: Float,
    ): BatchResult {
        val transcriber = SherpaTranscriber(context)
        val loadStart = SystemClock.elapsedRealtime()
        try {
            transcriber.load(
                SpeechConfig(
                    numThreads = threads,
                    autoClusterThreshold = autoClusterThreshold,
                    clusterPruneMinMs = pruneMinMs,
                    clusterPruneMinFraction = pruneMinFraction,
                ),
            )
            val modelLoadMs = SystemClock.elapsedRealtime() - loadStart
            val results = ArrayList<BatchRun>(runs)
            repeat(runs) { index ->
                results += runBatchOnce(
                    transcriber = transcriber,
                    wav = wav,
                    audioMs = audioMs,
                    index = index + 1,
                    expectedSpeakers = expectedSpeakers,
                )
            }
            return BatchResult(modelLoadMs = modelLoadMs, runs = results)
        } finally {
            runCatching { transcriber.release() }
        }
    }

    private suspend fun runBatchOnce(
        transcriber: SherpaTranscriber,
        wav: File,
        audioMs: Long,
        index: Int,
        expectedSpeakers: Int?,
    ): BatchRun {
        val startedAt = SystemClock.elapsedRealtime()
        var recognitionStart = 0L
        var doneAt = 0L
        var segments = emptyList<Segment>()

        transcriber.transcribe(wav, expectedSpeakers).collect { event ->
            when (event) {
                is TranscriptionEvent.Progress -> {
                    if (event.stage == Stage.RECOGNITION && recognitionStart == 0L) {
                        recognitionStart = SystemClock.elapsedRealtime()
                    }
                }

                is TranscriptionEvent.SegmentReady -> Unit

                is TranscriptionEvent.Done -> {
                    doneAt = SystemClock.elapsedRealtime()
                    segments = event.segments
                }

                is TranscriptionEvent.Failed -> throw event.cause
            }
        }

        check(doneAt > 0L) { "Batch-поток завершился без Done" }
        val boundary = if (recognitionStart > 0L) recognitionStart else doneAt
        val preparationMs = boundary - startedAt

        return BatchRun(
            index = index,
            audioMs = audioMs,
            totalMs = doneAt - startedAt,
            diarizationMs = if (expectedSpeakers == 1) 0L else preparationMs,
            recognitionMs = doneAt - boundary,
            preparationMs = if (expectedSpeakers == 1) preparationMs else 0L,
            pssMb = currentPssMb(context),
            nativeHeapMb = nativeHeapMb(),
            segments = segments,
        )
    }

    private suspend fun runStreaming(
        samples: FloatArray,
        audioMs: Long,
        threads: Int,
        runs: Int,
        chunkMs: Int,
        onlineClusterThreshold: Float,
        pruneMinMs: Int,
        pruneMinFraction: Float,
        speakerChangeThreshold: Float,
        vad: VadConfig,
        agc: AgcConfig,
        pace: Float,
        dumpEmbeddings: Boolean,
        reclusterThreshold: Float,
    ): StreamingResult {
        val transcriber = SherpaStreamingTranscriber(context)
        val loadStart = SystemClock.elapsedRealtime()
        try {
            transcriber.load(
                SpeechConfig(
                    numThreads = threads,
                    onlineClusterThreshold = onlineClusterThreshold,
                    clusterPruneMinMs = pruneMinMs,
                    clusterPruneMinFraction = pruneMinFraction,
                    speakerChangeThreshold = speakerChangeThreshold,
                    vad = vad,
                    agc = agc,
                    speakerReclusterThreshold = reclusterThreshold,
                ),
            )
            val modelLoadMs = SystemClock.elapsedRealtime() - loadStart
            val results = ArrayList<StreamingRun>(runs)
            repeat(runs) { index ->
                results += runStreamingOnce(
                    transcriber = transcriber,
                    samples = samples,
                    audioMs = audioMs,
                    index = index + 1,
                    chunkMs = chunkMs,
                    pace = pace,
                    dumpEmbeddings = dumpEmbeddings,
                )
            }
            return StreamingResult(modelLoadMs = modelLoadMs, runs = results)
        } finally {
            runCatching { transcriber.release() }
        }
    }

    private suspend fun runStreamingOnce(
        transcriber: SherpaStreamingTranscriber,
        samples: FloatArray,
        audioMs: Long,
        index: Int,
        chunkMs: Int,
        pace: Float,
        dumpEmbeddings: Boolean,
    ): StreamingRun {
        val chunkSize = (BENCHMARK_SAMPLE_RATE * chunkMs / 1000).coerceAtLeast(1)
        val chunkCount = (samples.size + chunkSize - 1) / chunkSize
        // Когда каждый чанк ушёл в транскрайбер, мс от старта прогона. По нему
        // считается задержка: «конец фразы в аудио был подан» → «пришёл текст».
        val fedAt = AtomicLongArray(chunkCount)
        // Насколько подача отстала от расписания реального времени. Это аудио,
        // которое живому микрофону пришлось бы где-то буферизовать.
        val maxFeedLagMs = AtomicLong(0L)
        val feedEndAt = AtomicLong(0L)
        var firstSpeechAt = 0L
        var firstUtteranceAt = 0L
        var doneAt = 0L
        var segments = emptyList<Segment>()
        val events = ArrayList<StreamingEventRecord>()

        fun fedAtMs(audioPositionMs: Long): Long {
            val sample = (audioPositionMs * BENCHMARK_SAMPLE_RATE / 1000L - 1L).coerceAtLeast(0L)
            val chunk = (sample / chunkSize).toInt().coerceIn(0, chunkCount - 1)
            return fedAt.get(chunk)
        }

        var embeddings: List<EmbeddingRecord>? = null
        transcriber.debugRecordsSink = if (dumpEmbeddings) {
            { records ->
                embeddings = records.map {
                    EmbeddingRecord(it.segment.startMs, it.segment.endMs, it.segment.speaker, it.embedding)
                }
            }
        } else {
            null
        }

        val startedAt = SystemClock.elapsedRealtime()
        val audio = flow {
            var offset = 0
            var chunkIndex = 0
            while (offset < samples.size) {
                val end = minOf(offset + chunkSize, samples.size)
                if (pace > 0f) {
                    // Микрофон отдаёт кусок [offset, end) не раньше, чем он «прозвучал».
                    val dueAt = startedAt +
                        (end * 1000.0 / BENCHMARK_SAMPLE_RATE / pace).roundToLong()
                    val now = SystemClock.elapsedRealtime()
                    if (dueAt > now) {
                        delay(dueAt - now)
                    } else {
                        maxFeedLagMs.accumulateAndGet(now - dueAt) { a, b -> maxOf(a, b) }
                    }
                }
                fedAt.set(chunkIndex, SystemClock.elapsedRealtime() - startedAt)
                emit(samples.copyOfRange(offset, end))
                offset = end
                chunkIndex++
            }
            feedEndAt.set(SystemClock.elapsedRealtime() - startedAt)
        }

        transcriber.transcribeStream(audio).collect { event ->
            val now = SystemClock.elapsedRealtime()
            val atMs = now - startedAt
            when (event) {
                is StreamingEvent.SpeechStarted -> {
                    if (firstSpeechAt == 0L) firstSpeechAt = atMs
                    events += StreamingEventRecord(
                        type = "speech",
                        atMs = atMs,
                        startMs = event.startMs,
                        endMs = null,
                        speaker = null,
                        latencyMs = atMs - fedAtMs(event.startMs),
                    )
                }

                is StreamingEvent.Utterance -> {
                    if (firstUtteranceAt == 0L) firstUtteranceAt = atMs
                    val segment = event.segment
                    events += StreamingEventRecord(
                        type = "utterance",
                        atMs = atMs,
                        startMs = segment.startMs,
                        endMs = segment.endMs,
                        speaker = segment.speaker,
                        latencyMs = atMs - fedAtMs(segment.endMs),
                    )
                }

                is StreamingEvent.Done -> {
                    doneAt = now
                    segments = event.segments
                }

                is StreamingEvent.Failed -> throw event.cause
            }
        }

        check(doneAt > 0L) { "Streaming-поток завершился без Done" }
        val totalMs = doneAt - startedAt
        val feedMs = feedEndAt.get().takeIf { it > 0L } ?: totalMs

        return StreamingRun(
            index = index,
            audioMs = audioMs,
            totalMs = totalMs,
            feedMs = feedMs,
            drainMs = (totalMs - feedMs).coerceAtLeast(0L),
            firstSpeechMs = firstSpeechAt,
            firstUtteranceMs = firstUtteranceAt,
            maxFeedLagMs = maxFeedLagMs.get(),
            pssMb = currentPssMb(context),
            nativeHeapMb = nativeHeapMb(),
            segments = segments,
            events = events,
            embeddings = embeddings,
        )
    }

    private fun requiredArg(name: String): String =
        args.getString(name)?.takeIf { it.isNotBlank() }
            ?: error("Не задан аргумент -e $name <значение>")

    private fun positiveInt(name: String, default: Int): Int {
        val raw = args.getString(name)?.takeIf { it.isNotBlank() } ?: return default
        val value = raw.toIntOrNull()
            ?: error("$name должен быть целым числом, получено: $raw")
        require(value >= 1) { "$name должен быть >= 1, получено: $value" }
        return value
    }

    private fun nonNegativeInt(name: String, default: Int): Int {
        val raw = args.getString(name)?.takeIf { it.isNotBlank() } ?: return default
        val value = raw.toIntOrNull()
            ?: error("$name должен быть целым числом, получено: $raw")
        require(value >= 0) { "$name должен быть >= 0, получено: $value" }
        return value
    }

    private fun nonNegativeFloat(name: String, default: Float): Float {
        val raw = args.getString(name)?.takeIf { it.isNotBlank() } ?: return default
        val value = raw.toFloatOrNull()
            ?: error("$name должен быть числом, получено: $raw")
        require(value >= 0f && value.isFinite()) { "$name должен быть >= 0, получено: $value" }
        return value
    }

    private fun onOff(name: String, default: Boolean): Boolean {
        val raw = args.getString(name)?.trim()?.lowercase(Locale.US)?.takeIf { it.isNotEmpty() }
            ?: return default
        return when (raw) {
            "on", "true", "1" -> true
            "off", "false", "0" -> false
            else -> error("$name должен быть on или off, получено: $raw")
        }
    }

    private fun floatArg(name: String, default: Float): Float {
        val raw = args.getString(name)?.takeIf { it.isNotBlank() } ?: return default
        return raw.toFloatOrNull()?.takeIf { it.isFinite() }
            ?: error("$name должен быть числом, получено: $raw")
    }

    private fun threshold(name: String, default: Float): Float {
        val raw = args.getString(name)?.takeIf { it.isNotBlank() } ?: return default
        val value = raw.toFloatOrNull()
            ?: error("$name должен быть числом, получено: $raw")
        require(value in 0f..1f) { "$name должен быть в 0..1, получено: $value" }
        return value
    }

    private fun outputFile(): File {
        val raw = args.getString("out")?.takeIf { it.isNotBlank() }
        val file = if (raw != null) {
            File(raw)
        } else {
            val dir = context.getExternalFilesDir(null)
                ?: error("External files dir недоступен: некуда сохранить результат")
            File(dir, "bench-results/latest.json")
        }
        require(file.extension.equals("json", ignoreCase = true)) {
            "out должен указывать на .json, получено: ${file.absolutePath}"
        }
        file.parentFile?.mkdirs()
        return file
    }

    private fun writeLatest(outFile: File, json: String, table: String) {
        val dir = outFile.parentFile ?: return
        val latestJson = File(dir, "latest.json")
        val latestTxt = File(dir, "latest.txt")
        if (outFile.canonicalFile != latestJson.canonicalFile) {
            latestJson.writeText(json)
            latestTxt.writeText(table)
        }
    }

    private companion object {
        val MODES = setOf("batch", "streaming", "both")
    }
}
