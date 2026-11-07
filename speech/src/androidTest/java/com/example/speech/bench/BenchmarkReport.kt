package com.example.speech.bench

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.Process
import com.example.speech.api.Segment
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt
import kotlin.math.roundToLong

internal const val BENCHMARK_SCHEMA_VERSION = 1
internal const val BENCHMARK_SAMPLE_RATE = 16_000

internal data class WavInfo(
    val durationMs: Long,
    val samples: Int,
)

internal fun readWavInfo(file: File): WavInfo {
    require(file.isFile) { "WAV не найден: ${file.absolutePath}" }
    val fileLength = file.length()
    require(fileLength >= 44) { "WAV короче 44 байт: ${file.absolutePath}" }

    RandomAccessFile(file, "r").use { raf ->
        val header = ByteArray(44)
        raf.readFully(header)
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

        fun fourCc(offset: Int): String =
            String(header, offset, 4, Charsets.US_ASCII)

        require(fourCc(0) == "RIFF") { "Это не WAV (ожидался RIFF): ${file.absolutePath}" }
        require(fourCc(8) == "WAVE") { "Это не WAV (ожидался WAVE): ${file.absolutePath}" }
        require(fourCc(12) == "fmt ") {
            "WAV должен начинаться с fmt-чанка: ${file.absolutePath}"
        }
        require(buffer.getInt(16) == 16) {
            "Ожидался fmt-чанк размером 16 байт: ${file.absolutePath}"
        }

        val format = buffer.getShort(20).toInt()
        val channels = buffer.getShort(22).toInt()
        val sampleRate = buffer.getInt(24)
        val bitsPerSample = buffer.getShort(34).toInt()

        require(format == 1) { "WAV должен быть PCM: ${file.absolutePath}" }
        require(channels == 1) { "WAV должен быть моно: ${file.absolutePath}" }
        require(sampleRate == BENCHMARK_SAMPLE_RATE) {
            "WAV должен быть 16 кГц, получено $sampleRate: ${file.absolutePath}"
        }
        require(bitsPerSample == 16) { "WAV должен быть PCM16: ${file.absolutePath}" }
        require(fourCc(36) == "data") {
            "WAV должен содержать data-чанк сразу после fmt: ${file.absolutePath}"
        }

        val declared = buffer.getInt(40).toLong() and 0xFFFFFFFFL
        val available = fileLength - 44
        val dataSize = when {
            declared == 0L -> available
            declared > available -> available
            else -> declared
        }
        val sampleCount = (dataSize / 2L).toInt()
        require(sampleCount > 0) { "В WAV нет аудиоданных: ${file.absolutePath}" }

        return WavInfo(
            durationMs = sampleCount * 1000L / BENCHMARK_SAMPLE_RATE,
            samples = sampleCount,
        )
    }
}

internal data class BenchmarkDevice(
    val model: String,
    val device: String,
    val android: String,
    val sdk: Int,
    val abi: String,
    val cores: Int,
    val ramGb: Double,
    val isEmulator: Boolean,
)

internal data class BenchmarkAudio(
    val path: String,
    val durationMs: Long,
    val samples: Int,
)

internal data class BenchmarkConfig(
    val threads: Int,
    val runs: Int,
    val speakers: String,
    val chunkMs: Int,
    val autoClusterThreshold: Float,
    val onlineClusterThreshold: Float,
    val clusterPruneMinMs: Int,
    val clusterPruneMinFraction: Float,
    val speakerChangeThreshold: Float,
)

internal data class BatchRun(
    val index: Int,
    val audioMs: Long,
    val totalMs: Long,
    val diarizationMs: Long,
    val recognitionMs: Long,
    val preparationMs: Long,
    val pssMb: Int,
    val nativeHeapMb: Int,
    val segments: List<Segment>,
) {
    val rtf: Double get() = totalMs.toDouble() / audioMs.coerceAtLeast(1)
    val asrRtf: Double get() = recognitionMs.toDouble() / audioMs.coerceAtLeast(1)
    val diarizationRtf: Double get() = diarizationMs.toDouble() / audioMs.coerceAtLeast(1)
    val speedX: Double get() = audioMs.toDouble() / totalMs.coerceAtLeast(1)
    val speakerCount: Int get() = segments.map { it.speaker }.distinct().size
}

internal data class BatchResult(
    val modelLoadMs: Long,
    val runs: List<BatchRun>,
) {
    val medianTotalMs: Long get() = median(runs.map { it.totalMs.toDouble() }).roundToLong()
    val medianDiarizationMs: Long
        get() = median(runs.map { it.diarizationMs.toDouble() }).roundToLong()
    val medianRecognitionMs: Long
        get() = median(runs.map { it.recognitionMs.toDouble() }).roundToLong()
    val medianRtf: Double get() = median(runs.map { it.rtf })
    val medianAsrRtf: Double get() = median(runs.map { it.asrRtf })
    val medianDiarizationRtf: Double get() = median(runs.map { it.diarizationRtf })
    val medianSpeedX: Double get() = median(runs.map { it.speedX })
    val medianPssMb: Int get() = median(runs.map { it.pssMb.toDouble() }).roundToInt()
    val medianSpeakerCount: Int
        get() = median(runs.map { it.speakerCount.toDouble() }).roundToInt()
    val medianSegments: Int get() = median(runs.map { it.segments.size.toDouble() }).roundToInt()
}

internal data class StreamingRun(
    val index: Int,
    val audioMs: Long,
    val totalMs: Long,
    val feedMs: Long,
    val drainMs: Long,
    val firstSpeechMs: Long,
    val firstUtteranceMs: Long,
    val pssMb: Int,
    val nativeHeapMb: Int,
    val segments: List<Segment>,
) {
    val rtf: Double get() = totalMs.toDouble() / audioMs.coerceAtLeast(1)
    val speedX: Double get() = audioMs.toDouble() / totalMs.coerceAtLeast(1)
    val speakerCount: Int get() = segments.map { it.speaker }.distinct().size
}

internal data class StreamingResult(
    val modelLoadMs: Long,
    val runs: List<StreamingRun>,
) {
    val medianTotalMs: Long get() = median(runs.map { it.totalMs.toDouble() }).roundToLong()
    val medianFeedMs: Long get() = median(runs.map { it.feedMs.toDouble() }).roundToLong()
    val medianDrainMs: Long get() = median(runs.map { it.drainMs.toDouble() }).roundToLong()
    val medianFirstUtteranceMs: Long
        get() = median(runs.map { it.firstUtteranceMs.toDouble() }).roundToLong()
    val medianRtf: Double get() = median(runs.map { it.rtf })
    val medianSpeedX: Double get() = median(runs.map { it.speedX })
    val medianPssMb: Int get() = median(runs.map { it.pssMb.toDouble() }).roundToInt()
    val medianSpeakerCount: Int
        get() = median(runs.map { it.speakerCount.toDouble() }).roundToInt()
    val medianSegments: Int get() = median(runs.map { it.segments.size.toDouble() }).roundToInt()
}

internal data class BenchmarkReport(
    val generatedAt: String,
    val label: String,
    val mode: String,
    val audio: BenchmarkAudio,
    val config: BenchmarkConfig,
    val device: BenchmarkDevice,
    val batch: BatchResult?,
    val streaming: StreamingResult?,
)

internal fun readBenchmarkDevice(context: Context): BenchmarkDevice {
    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val memoryInfo = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
    val manufacturerModel = listOf(Build.MANUFACTURER, Build.MODEL)
        .filter { it.isNotBlank() }
        .joinToString(" ")
    val emulator = Build.FINGERPRINT.startsWith("generic") ||
        Build.FINGERPRINT.contains("emulator", ignoreCase = true) ||
        Build.MODEL.contains("sdk_gphone", ignoreCase = true) ||
        Build.PRODUCT.contains("sdk", ignoreCase = true)

    return BenchmarkDevice(
        model = manufacturerModel.ifBlank { "Android device" },
        device = Build.DEVICE,
        android = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        sdk = Build.VERSION.SDK_INT,
        abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
        cores = Runtime.getRuntime().availableProcessors(),
        ramGb = memoryInfo.totalMem / (1024.0 * 1024.0 * 1024.0),
        isEmulator = emulator,
    )
}

@Suppress("DEPRECATION")
internal fun currentPssMb(context: Context): Int {
    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    return activityManager.getProcessMemoryInfo(intArrayOf(Process.myPid()))
        .firstOrNull()
        ?.totalPss
        ?.div(1024)
        ?: 0
}

internal fun nativeHeapMb(): Int =
    (Debug.getNativeHeapAllocatedSize() / (1024L * 1024L)).toInt()

internal fun isoNow(): String {
    val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
    format.timeZone = TimeZone.getDefault()
    return format.format(Date())
}

private fun median(values: List<Double>): Double {
    if (values.isEmpty()) return 0.0
    val sorted = values.sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 1) {
        sorted[middle]
    } else {
        (sorted[middle - 1] + sorted[middle]) / 2.0
    }
}

internal fun BenchmarkReport.toJson(): String = JSONObject().apply {
    put("schemaVersion", BENCHMARK_SCHEMA_VERSION)
    put("generatedAt", generatedAt)
    put("label", label)
    put("mode", mode)
    put("audio", audio.toJson())
    put("config", config.toJson())
    put("device", device.toJson())
    batch?.let { put("batch", it.toJson()) }
    streaming?.let { put("streaming", it.toJson()) }
}.toString(2)

private fun BenchmarkAudio.toJson(): JSONObject = JSONObject().apply {
    put("path", path)
    put("durationMs", durationMs)
    put("sampleRate", BENCHMARK_SAMPLE_RATE)
    put("channels", 1)
    put("bitsPerSample", 16)
    put("samples", samples)
}

private fun BenchmarkConfig.toJson(): JSONObject = JSONObject().apply {
    put("threads", threads)
    put("runs", runs)
    put("speakers", speakers)
    put("chunkMs", chunkMs)
    put("autoClusterThreshold", autoClusterThreshold)
    put("onlineClusterThreshold", onlineClusterThreshold)
    put("clusterPruneMinMs", clusterPruneMinMs)
    put("clusterPruneMinFraction", clusterPruneMinFraction)
    put("speakerChangeThreshold", speakerChangeThreshold)
}

private fun BenchmarkDevice.toJson(): JSONObject = JSONObject().apply {
    put("model", model)
    put("device", device)
    put("android", android)
    put("sdk", sdk)
    put("abi", abi)
    put("cores", cores)
    put("ramGb", String.format(Locale.US, "%.1f", ramGb))
    put("isEmulator", isEmulator)
}

private fun BatchResult.toJson(): JSONObject = JSONObject().apply {
    put("modelLoadMs", modelLoadMs)
    put(
        "summary",
        JSONObject().apply {
            put("medianTotalMs", medianTotalMs)
            put("medianDiarizationMs", medianDiarizationMs)
            put("medianRecognitionMs", medianRecognitionMs)
            put("medianRtf", medianRtf)
            put("medianAsrRtf", medianAsrRtf)
            put("medianDiarizationRtf", medianDiarizationRtf)
            put("medianSpeedX", medianSpeedX)
            put("medianPssMb", medianPssMb)
            put("medianSpeakerCount", medianSpeakerCount)
            put("medianSegments", medianSegments)
        },
    )
    put("runs", JSONArray().apply { runs.forEach { put(it.toJson()) } })
}

private fun BatchRun.toJson(): JSONObject = JSONObject().apply {
    put("run", index)
    put("totalMs", totalMs)
    put("diarizationMs", diarizationMs)
    put("recognitionMs", recognitionMs)
    put("preparationMs", preparationMs)
    put("rtf", rtf)
    put("asrRtf", asrRtf)
    put("diarizationRtf", diarizationRtf)
    put("speedX", speedX)
    put("pssMb", pssMb)
    put("nativeHeapMb", nativeHeapMb)
    put("speakerCount", speakerCount)
    put("segments", segments.size)
    put("segmentList", segments.toJson())
}

private fun StreamingResult.toJson(): JSONObject = JSONObject().apply {
    put("modelLoadMs", modelLoadMs)
    put(
        "summary",
        JSONObject().apply {
            put("medianTotalMs", medianTotalMs)
            put("medianFeedMs", medianFeedMs)
            put("medianDrainMs", medianDrainMs)
            put("medianFirstUtteranceMs", medianFirstUtteranceMs)
            put("medianRtf", medianRtf)
            put("medianSpeedX", medianSpeedX)
            put("medianPssMb", medianPssMb)
            put("medianSpeakerCount", medianSpeakerCount)
            put("medianSegments", medianSegments)
        },
    )
    put("runs", JSONArray().apply { runs.forEach { put(it.toJson()) } })
}

private fun StreamingRun.toJson(): JSONObject = JSONObject().apply {
    put("run", index)
    put("totalMs", totalMs)
    put("feedMs", feedMs)
    put("drainMs", drainMs)
    put("firstSpeechMs", firstSpeechMs)
    put("firstUtteranceMs", firstUtteranceMs)
    put("rtf", rtf)
    put("speedX", speedX)
    put("pssMb", pssMb)
    put("nativeHeapMb", nativeHeapMb)
    put("speakerCount", speakerCount)
    put("segments", segments.size)
    put("segmentList", segments.toJson())
}

private fun List<Segment>.toJson(): JSONArray = JSONArray().apply {
    forEach { segment ->
        put(
            JSONObject().apply {
                put("speaker", segment.speaker)
                put("startMs", segment.startMs)
                put("endMs", segment.endMs)
                put("text", segment.text)
            },
        )
    }
}

internal fun BenchmarkReport.formatTable(): String {
    val out = StringBuilder()
    out.appendLine("Speech benchmark")
    out.appendLine(
        "audio:  $label (${formatSeconds(audio.durationMs)}, " +
            "$BENCHMARK_SAMPLE_RATE Hz, mono, PCM16)",
    )
    out.appendLine(
        "device: ${device.model} / ${device.abi} / Android ${device.android} / " +
            "${device.cores} cores / ${formatGb(device.ramGb)} GB / " +
            "emulator=${device.isEmulator}",
    )
    out.appendLine(
        "config: mode=$mode threads=${config.threads} runs=${config.runs} " +
            "speakers=${config.speakers} chunk=${config.chunkMs}ms " +
            "autoThreshold=${config.autoClusterThreshold} " +
            "onlineThreshold=${config.onlineClusterThreshold} " +
            "prune=${config.clusterPruneMinMs}ms/${config.clusterPruneMinFraction} " +
            "change=${config.speakerChangeThreshold}",
    )

    batch?.let { result ->
        out.appendLine()
        out.appendLine("batch (model load ${formatSeconds(result.modelLoadMs)})")
        out.appendLine(
            "  run   total     diar      asr      rtf   asrRtf   speed    pss  spk  seg",
        )
        result.runs.forEach { run ->
            out.appendLine(
                String.format(
                    Locale.US,
                    "  %3d  %7s  %7s  %7s  %7.3f  %7.3f  %6s  %5d  %3d  %3d",
                    run.index,
                    formatSeconds(run.totalMs),
                    formatSeconds(run.diarizationMs),
                    formatSeconds(run.recognitionMs),
                    run.rtf,
                    run.asrRtf,
                    formatSpeed(run.speedX),
                    run.pssMb,
                    run.speakerCount,
                    run.segments.size,
                ),
            )
        }
        out.appendLine(
            String.format(
                Locale.US,
                "  %3s  %7s  %7s  %7s  %7.3f  %7.3f  %6s  %5d  %3d  %3d",
                "med",
                formatSeconds(result.medianTotalMs),
                formatSeconds(result.medianDiarizationMs),
                formatSeconds(result.medianRecognitionMs),
                result.medianRtf,
                result.medianAsrRtf,
                formatSpeed(result.medianSpeedX),
                result.medianPssMb,
                result.medianSpeakerCount,
                result.medianSegments,
            ),
        )
    }

    streaming?.let { result ->
        out.appendLine()
        out.appendLine("streaming (model load ${formatSeconds(result.modelLoadMs)})")
        out.appendLine(
            "  run   total     feed    drain    first      rtf   speed    pss  spk  seg",
        )
        result.runs.forEach { run ->
            out.appendLine(
                String.format(
                    Locale.US,
                    "  %3d  %7s  %7s  %7s  %7s  %7.3f  %6s  %5d  %3d  %3d",
                    run.index,
                    formatSeconds(run.totalMs),
                    formatSeconds(run.feedMs),
                    formatSeconds(run.drainMs),
                    formatSeconds(run.firstUtteranceMs),
                    run.rtf,
                    formatSpeed(run.speedX),
                    run.pssMb,
                    run.speakerCount,
                    run.segments.size,
                ),
            )
        }
        out.appendLine(
            String.format(
                Locale.US,
                "  %3s  %7s  %7s  %7s  %7s  %7.3f  %6s  %5d  %3d  %3d",
                "med",
                formatSeconds(result.medianTotalMs),
                formatSeconds(result.medianFeedMs),
                formatSeconds(result.medianDrainMs),
                formatSeconds(result.medianFirstUtteranceMs),
                result.medianRtf,
                formatSpeed(result.medianSpeedX),
                result.medianPssMb,
                result.medianSpeakerCount,
                result.medianSegments,
            ),
        )
    }

    return out.toString()
}

private fun formatSeconds(ms: Long): String =
    String.format(Locale.US, "%.2fs", ms / 1000.0)

private fun formatSpeed(value: Double): String =
    String.format(Locale.US, "%.1fx", value)

private fun formatGb(value: Double): String =
    String.format(Locale.US, "%.1f", value)
