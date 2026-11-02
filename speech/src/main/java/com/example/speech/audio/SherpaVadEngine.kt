package com.example.speech.audio

import android.content.res.AssetManager
import com.example.speech.api.VadConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

internal class SherpaVadEngine(
    assetManager: AssetManager,
    modelPath: String,
    config: VadConfig,
    numThreads: Int = 1,
) : VadEngine {

    private val maxSpeechSamples =
        (config.maxSpeechMs.toLong() * VadSegmenter.SAMPLE_RATE / 1000L).toInt().coerceAtLeast(1)

    private val vad = Vad(
        assetManager = assetManager,
        config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = modelPath,
                threshold = config.threshold,
                minSilenceDuration = config.minSilenceMs / 1000f,
                minSpeechDuration = config.minSpeechMs / 1000f,
                windowSize = VadSegmenter.WINDOW_SIZE,
                maxSpeechDuration = config.maxSpeechMs / 1000f,
            ),
            sampleRate = VadSegmenter.SAMPLE_RATE,
            numThreads = numThreads,
            provider = "cpu",
            debug = false,
        ),
    )

    override fun accept(samples: FloatArray) = vad.acceptWaveform(samples)

    override fun drain(): List<VadEngine.RawSegment> {
        val result = ArrayList<VadEngine.RawSegment>()
        while (!vad.empty()) {
            val segment = vad.front()
            vad.pop()
            result += splitLongVadSegment(
                startSample = segment.start.toLong(),
                samples = segment.samples,
                maxSamples = maxSpeechSamples,
            )
        }
        return result
    }

    override fun flush() = vad.flush()

    override fun isSpeechDetected(): Boolean = vad.isSpeechDetected()

    override fun reset() = vad.reset()

    override fun release() = vad.release()
}
