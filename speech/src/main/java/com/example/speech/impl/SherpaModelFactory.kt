package com.example.speech.impl

import android.content.res.AssetManager
import com.example.speech.api.SpeechConfig
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig

internal object SherpaModelFactory {
    const val SAMPLE_RATE = 16_000

    fun createRecognizer(
        assetManager: AssetManager,
        config: SpeechConfig,
        asrModelPath: String,
        tokensPath: String,
    ): OfflineRecognizer = OfflineRecognizer(
        assetManager = assetManager,
        config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(
                sampleRate = SAMPLE_RATE,
                featureDim = 64,
            ),
            modelConfig = OfflineModelConfig(
                nemo = OfflineNemoEncDecCtcModelConfig(model = asrModelPath),
                tokens = tokensPath,
                numThreads = config.numThreads,
                debug = false,
                provider = "cpu",
                modelType = "nemo_ctc",
            ),
            decodingMethod = "greedy_search",
        ),
    )
}
