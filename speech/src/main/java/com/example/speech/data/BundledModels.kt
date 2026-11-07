package com.example.speech.data

import android.content.Context
import android.content.res.AssetManager
import com.example.speech.api.AsrModel
import com.example.speech.api.EmbeddingModel
import java.security.MessageDigest

internal object BundledModels {
    const val ASR_RNNT_ENCODER = "models/gigaam_v3_e2e_rnnt_encoder_int8.onnx"
    const val ASR_RNNT_DECODER = "models/gigaam_v3_e2e_rnnt_decoder.onnx"
    const val ASR_RNNT_JOINER = "models/gigaam_v3_e2e_rnnt_joiner.onnx"
    const val ASR_RNNT_TOKENS = "asr/gigaam_v3_e2e_rnnt_tokens.txt"
    const val ASR_CTC = "models/gigaam_v3_e2e_ctc_int8.onnx"
    const val ASR_CTC_TOKENS = "asr/gigaam_v3_e2e_ctc_tokens.txt"
    const val ASR_EN_ENCODER = "models/parakeet_tdt_110m_en_encoder_int8.onnx"
    const val ASR_EN_DECODER = "models/parakeet_tdt_110m_en_decoder_int8.onnx"
    const val ASR_EN_JOINER = "models/parakeet_tdt_110m_en_joiner_int8.onnx"
    const val ASR_EN_TOKENS = "asr/parakeet_tdt_110m_en_tokens.txt"
    const val EMBEDDING_CAMPLUS = "models/speaker_embedding_camplus.onnx"
    const val EMBEDDING_ERES2NET = "models/speaker_embedding_eres2net.onnx"
    const val SEGMENTATION = "asr/segmentation.onnx"
    const val VAD = "vad/silero_vad.onnx"

    fun embeddingFor(model: EmbeddingModel): String =
        ModelRegistry.embeddingFor(model).assetPath

    fun isPresent(context: Context): Boolean = missing(context).isEmpty()

    /** Выбранная embedding-модель есть в APK (ERes2Net — необязательная). */
    fun requireEmbedding(context: Context, model: EmbeddingModel) {
        val file = ModelRegistry.embeddingFor(model)
        check(isPresent(context, file)) {
            "В APK нет embedding-модели ${file.assetPath} (${file.sizeBytes} байт). " +
                "Положите её в speech/src/main/assets/models/."
        }
    }

    /** Файлы выбранной модели ASR есть в APK (e2e CTC — необязательная). */
    fun requireAsr(context: Context, model: AsrModel) {
        val missing = ModelRegistry.asrExtraFor(model).filterNot { isPresent(context, it) }
        check(missing.isEmpty()) {
            "В APK нет файлов модели ASR $model: ${missing.joinToString { "${it.assetPath} (${it.sizeBytes} байт)" }}. " +
                "Положите их в speech/src/main/assets/."
        }
    }

    /** Все файлы английской модели есть в APK (она необязательна). */
    fun isEnglishPresent(context: Context): Boolean = ModelRegistry.english.all { isPresent(context, it) }

    fun isPresent(context: Context, model: ModelFile): Boolean =
        assetSize(context.assets, model.assetPath) == model.sizeBytes

    fun requirePresent(context: Context) {
        val missing = missing(context)
        check(missing.isEmpty()) {
            "В APK отсутствуют модели: ${missing.joinToString()}. " +
                "Скачайте веса по INSTALL.md (раздел «2. Веса») в " +
                "speech/src/main/assets/. Проверьте noCompress в app/build.gradle.kts."
        }
    }

    fun verifyIntegrity(context: Context): Boolean {
        val assets = context.assets
        return entries.all { entry ->
            assetSize(assets, entry.path) == entry.sizeBytes &&
                sha256(assets, entry.path).equals(entry.sha256, ignoreCase = true)
        }
    }

    fun missing(context: Context): List<String> {
        val assets = context.assets
        return entries
            .filterNot { assetSize(assets, it.path) == it.sizeBytes }
            .map { it.path }
    }

    private fun assetSize(assets: AssetManager, path: String): Long = try {
        assets.openFd(path).use { it.length }
    } catch (_: Throwable) {
        -1L
    }

    private fun sha256(assets: AssetManager, path: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        assets.open(path).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        val bytes = digest.digest()
        val hex = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val value = bytes[i].toInt() and 0xFF
            hex[i * 2] = HEX[value ushr 4]
            hex[i * 2 + 1] = HEX[value and 0x0F]
        }
        return String(hex)
    }

    private fun modelEntry(model: ModelFile) =
        Entry(model.assetPath, model.sizeBytes, model.sha256)

    private val entries: List<Entry> = ModelRegistry.required.map(::modelEntry) + listOf(
        Entry(
            SEGMENTATION,
            1_540_506L,
            "d582f4b4c6b48205de7e0643c57df0df5615a3c176189be3fc461e9d18827b5d",
        ),
        Entry(
            VAD,
            643_854L,
            "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6",
        ),
    )

    private data class Entry(
        val path: String,
        val sizeBytes: Long,
        val sha256: String,
    )

    private const val HEX = "0123456789abcdef"
}
