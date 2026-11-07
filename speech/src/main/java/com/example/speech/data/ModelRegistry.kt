package com.example.speech.data

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

    val ASR = ModelFile(
        id = "asr_gigaam_v3",
        assetPath = BundledModels.ASR,
        sizeBytes = 319_869_121L,
        sha256 = "0aacb41f70f0f5aaac4b45dd430337b9e16b180f22c72af04db8516e7609c3c0",
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

    val required: List<ModelFile> = listOf(ASR, EMBEDDING_CAMPLUS, EMBEDDING_ERES2NET)

    val totalBytes: Long = required.sumOf { it.sizeBytes }

    fun embeddingFor(model: EmbeddingModel): ModelFile = when (model) {
        EmbeddingModel.CAMPLUS -> EMBEDDING_CAMPLUS
        EmbeddingModel.ERES2NET -> EMBEDDING_ERES2NET
    }
}
