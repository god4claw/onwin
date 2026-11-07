package com.example.llm

import android.content.Context
import com.example.speech.api.TextCorrector
import com.example.speech.impl.CompositeCorrector
import com.example.speech.impl.LexiconCorrector
import com.example.speech.impl.LlmCorrector
import java.io.File

/**
 * Собирает корректор расшифровки для приложения: словарь терминов плюс локальная
 * LLM, если её модель лежит на устройстве.
 *
 * Модель не пакуется в APK: 0,5 млрд параметров в int4 — это ~0,9 ГБ, они
 * скачиваются `llm/scripts/fetch-model.ps1` и заливаются на телефон
 * `scripts/push-llm-model.ps1`. Нет модели — нет и слоя LLM: приложение
 * работает как раньше, без ошибок и без деградации.
 *
 * Каталоги проверяются по порядку: внутренний `filesDir/llm`, затем внешний
 * `Android/data/<pkg>/files/llm` (туда удобно заливать через adb).
 */
object OnDeviceCorrector {

    /** Имя каталога с моделью внутри filesDir приложения. */
    const val MODEL_DIR_NAME = "llm"

    /**
     * Бюджет на одну реплику. Финальный проход идёт после расшифровки, спешить
     * некуда; 5 с хватает 0,5B-модели на 20–40 токенов ответа на телефоне.
     */
    const val BUDGET_MS = 5_000L

    /** Потолок ответа: реплика в 30 с — это 25–40 токенов, 96 хватает с запасом. */
    const val MAX_OUTPUT_TOKENS = 96

    /** Каталог с готовым комплектом ONNX GenAI или `null`, если модели нет. */
    fun modelDirectory(context: Context): File? =
        candidates(context).firstOrNull(LlmModelDirectory::isComplete)

    /** Есть ли на устройстве модель, с которой можно работать. */
    fun isAvailable(context: Context): Boolean = modelDirectory(context) != null

    /**
     * Корректор или `null`, если модели нет.
     *
     * @param lexicon пары «как услышали» → «как правильно» для слоя без модели.
     */
    fun create(context: Context, lexicon: Map<String, String> = emptyMap()): TextCorrector? {
        val dir = modelDirectory(context) ?: return null
        val layers = buildList {
            if (lexicon.isNotEmpty()) add(LexiconCorrector(lexicon))
            add(LlmCorrector(OnnxGenAiGenerator(dir), maxOutputTokens = MAX_OUTPUT_TOKENS, budgetMs = BUDGET_MS))
        }
        return CompositeCorrector(layers)
    }

    /** Каталоги, где приложение ищет модель, в порядке приоритета. */
    fun candidates(context: Context): List<File> = buildList {
        add(File(context.filesDir, MODEL_DIR_NAME))
        // Внешний каталог доступен для `adb push` без run-as.
        context.getExternalFilesDir(null)?.let { add(File(it, MODEL_DIR_NAME)) }
    }
}
