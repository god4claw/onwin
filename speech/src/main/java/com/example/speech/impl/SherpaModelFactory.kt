package com.example.speech.impl

import android.content.res.AssetManager
import com.example.speech.api.AsrModel
import com.example.speech.api.SpeechConfig
import com.example.speech.data.BundledModels
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig

internal object SherpaModelFactory {
    const val SAMPLE_RATE = 16_000

    /** Распознаватель выбранной [SpeechConfig.asrModel]; оба варианта — greedy, 64 mel. */
    fun createRecognizer(
        assetManager: AssetManager,
        config: SpeechConfig,
    ): OfflineRecognizer = OfflineRecognizer(
        assetManager = assetManager,
        config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(
                sampleRate = SAMPLE_RATE,
                featureDim = 64,
            ),
            modelConfig = modelConfig(config),
            decodingMethod = "greedy_search",
        ),
    )

    private fun modelConfig(config: SpeechConfig): OfflineModelConfig = when (config.asrModel) {
        AsrModel.E2E_CTC -> OfflineModelConfig(
            nemo = OfflineNemoEncDecCtcModelConfig(model = BundledModels.ASR_CTC),
            tokens = BundledModels.ASR_CTC_TOKENS,
            numThreads = config.numThreads,
            debug = false,
            provider = "cpu",
            modelType = "nemo_ctc",
        )
        AsrModel.E2E_RNNT -> OfflineModelConfig(
            transducer = OfflineTransducerModelConfig(
                encoder = BundledModels.ASR_RNNT_ENCODER,
                decoder = BundledModels.ASR_RNNT_DECODER,
                joiner = BundledModels.ASR_RNNT_JOINER,
            ),
            tokens = BundledModels.ASR_RNNT_TOKENS,
            numThreads = config.numThreads,
            debug = false,
            provider = "cpu",
            modelType = "nemo_transducer",
        )
    }
}
