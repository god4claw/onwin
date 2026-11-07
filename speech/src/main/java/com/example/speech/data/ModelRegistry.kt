package com.example.speech.data

import com.example.speech.api.AsrModel
import com.example.speech.api.EmbeddingModel

internal data class ModelFile(
    val id: String,
    val assetPath: String,
    val sizeBytes: Long,
    val sha256: String,
) {
    val fileName: String get() = assetPath.substringAfterLast('/')
}

/**
 * Каталог весов: размеры и SHA-256 для проверки целостности.
 *
 * `internal`: снаружи модуля веса не скачиваются и не проверяются — это
 * деталь реализации `BundledModels`.
 */
internal object ModelRegistry {

    /**
     * GigaAM-v3 e2e RNN-T — модель ASR по умолчанию. Релиз sherpa-onnx `asr-models`:
     * `sherpa-onnx-nemo-transducer-punct-giga-am-v3-russian-2025-12-16`
     * (`encoder.int8.onnx`, `decoder.onnx`, `joiner.onnx`, `tokens.txt`).
     */
    val ASR_RNNT_ENCODER = ModelFile(
        id = "asr_gigaam_v3_rnnt_encoder",
        assetPath = BundledModels.ASR_RNNT_ENCODER,
        sizeBytes = 224_570_820L,
        sha256 = "369f35a71bf288d3b8e0391fabd8dba5f2314088d440bca474056b7b4b6e66bf",
    )

    val ASR_RNNT_DECODER = ModelFile(
        id = "asr_gigaam_v3_rnnt_decoder",
        assetPath = BundledModels.ASR_RNNT_DECODER,
        sizeBytes = 4_600_132L,
        sha256 = "38fc7475443ea2a26f63211ca350f73ac50fff824ab7a3876ee2bd610c53bbc4",
    )

    val ASR_RNNT_JOINER = ModelFile(
        id = "asr_gigaam_v3_rnnt_joiner",
        assetPath = BundledModels.ASR_RNNT_JOINER,
        sizeBytes = 2_712_896L,
        sha256 = "602ff7017a93311aad34df1437c8d7f49911353c13d6eae7a6ee7b041339465c",
    )

    val ASR_RNNT_TOKENS = ModelFile(
        id = "asr_gigaam_v3_rnnt_tokens",
        assetPath = BundledModels.ASR_RNNT_TOKENS,
        sizeBytes = 13_354L,
        sha256 = "39abae20e692998290c574e606f11a9edef2902a1995463fcff63d1490cf22b7",
    )

    /** Прежняя модель GigaAM-v3 e2e CTC; необязательная — нужна только при [AsrModel.E2E_CTC]. */
    val ASR_CTC = ModelFile(
        id = "asr_gigaam_v3_ctc",
        assetPath = BundledModels.ASR_CTC,
        sizeBytes = 319_869_121L,
        sha256 = "0aacb41f70f0f5aaac4b45dd430337b9e16b180f22c72af04db8516e7609c3c0",
    )

    val ASR_CTC_TOKENS = ModelFile(
        id = "asr_gigaam_v3_ctc_tokens",
        assetPath = BundledModels.ASR_CTC_TOKENS,
        sizeBytes = 2_262L,
        sha256 = "8cb49db32f35bb382af88d2a8ed501aca0ef31e85d0c995c3a210256d6d353a7",
    )

    val EMBEDDING_CAMPLUS = ModelFile(
        id = "embedding_camplus",
        assetPath = BundledModels.EMBEDDING_CAMPLUS,
        sizeBytes = 28_281_138L,
        sha256 = "f682b514c05d947ee3fa91cd6ec6c5c7543479a128373fa29b1faedccd21fd11",
    )

    /**
     * 3D-Speaker ERes2Net (VoxCeleb): `3dspeaker_speech_eres2net_sv_en_voxceleb_16k.onnx`
     * из релиза sherpa-onnx `speaker-recongition-models`. Модель по умолчанию.
     */
    val EMBEDDING_ERES2NET = ModelFile(
        id = "embedding_eres2net",
        assetPath = BundledModels.EMBEDDING_ERES2NET,
        sizeBytes = 26_485_263L,
        sha256 = "c59158379255ad66e161679cca6af8d52d51e389e3224ab7d7a7baae295c2db5",
    )

    /**
     * NVIDIA Parakeet TDT 110M (английский) из релиза sherpa-onnx `asr-models`:
     * `sherpa-onnx-nemo-parakeet_tdt_transducer_110m-en-36000-int8`. Необязательная:
     * без неё `SpeechConfig.detectEnglish` ничего не делает.
     */
    val ASR_EN_ENCODER = ModelFile(
        id = "asr_parakeet_en_encoder",
        assetPath = BundledModels.ASR_EN_ENCODER,
        sizeBytes = 131_113_202L,
        sha256 = "0f35509ddeb9b39002fb077d979a9fe74f06eb0bc4dd5c34f512f82e5111d657",
    )

    val ASR_EN_DECODER = ModelFile(
        id = "asr_parakeet_en_decoder",
        assetPath = BundledModels.ASR_EN_DECODER,
        sizeBytes = 3_955_863L,
        sha256 = "f7c331c5504c2e593c76ed22b728e3f554af6c4a383dde862e719ced08b1da19",
    )

    val ASR_EN_JOINER = ModelFile(
        id = "asr_parakeet_en_joiner",
        assetPath = BundledModels.ASR_EN_JOINER,
        sizeBytes = 1_411_403L,
        sha256 = "bf7dff69e9f2cdbe9943d70da358f38b361c115ba0105bae7e908e0d6ec782f6",
    )

    val ASR_EN_TOKENS = ModelFile(
        id = "asr_parakeet_en_tokens",
        assetPath = BundledModels.ASR_EN_TOKENS,
        sizeBytes = 9_953L,
        sha256 = "450e56bd2f036fe5b6aa821865838cc5aa9d8b0106134ce9a9ba0664abe6cd10",
    )

    val english: List<ModelFile> = listOf(ASR_EN_ENCODER, ASR_EN_DECODER, ASR_EN_JOINER, ASR_EN_TOKENS)

    val required: List<ModelFile> = listOf(
        ASR_RNNT_ENCODER,
        ASR_RNNT_DECODER,
        ASR_RNNT_JOINER,
        ASR_RNNT_TOKENS,
        EMBEDDING_CAMPLUS,
        EMBEDDING_ERES2NET,
    )

    val totalBytes: Long = required.sumOf { it.sizeBytes }

    /** Файлы, которые нужны выбранной модели ASR сверх [required]. */
    fun asrExtraFor(model: AsrModel): List<ModelFile> = when (model) {
        AsrModel.E2E_RNNT -> emptyList()
        AsrModel.E2E_CTC -> listOf(ASR_CTC, ASR_CTC_TOKENS)
    }

    fun embeddingFor(model: EmbeddingModel): ModelFile = when (model) {
        EmbeddingModel.CAMPLUS -> EMBEDDING_CAMPLUS
        EmbeddingModel.ERES2NET -> EMBEDDING_ERES2NET
    }
}
