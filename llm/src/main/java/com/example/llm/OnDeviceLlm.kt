package com.example.llm

import android.content.Context
import com.example.speech.api.ProtocolAnalyzer
import com.example.speech.api.TextCorrector
import com.example.speech.impl.CompositeCorrector
import com.example.speech.impl.LexiconCorrector
import com.example.speech.impl.LlmCorrector
import com.example.speech.impl.LlmDialogueAnalyzer
import java.io.File

/**
 * Локальный LLM-слой приложения: правка реплик и разбор диалога на одном
 * генераторе.
 *
 * Модель одна на оба прохода: загрузить 0,5B дважды — это лишние секунды и
 * двойная память. Сначала идёт [corrector] по репликам, затем [analyzer]
 * по всему диалогу; контроллер вызывает их последовательно.
 *
 * Модели нет — `null`, и приложение работает как раньше.
 */
class OnDeviceLlm private constructor(
    val corrector: TextCorrector,
    val analyzer: LlmDialogueAnalyzer,
) {

    /** Протокол идёт через тот же анализатор и генератор, что и резюме. */
    val protocolAnalyzer: ProtocolAnalyzer get() = analyzer

    companion object {
        /** Каталог с моделью внутри filesDir приложения. */
        const val MODEL_DIR_NAME = "llm"

        /**
         * Бюджет на одну реплику. Финальный проход, спешить некуда; на телефоне
         * 0,5B отвечает за секунды, на x86_64-эмуляторе — заметно медленнее,
         * поэтому бюджет с запасом: лучше медленно, чем без коррекции.
         */
        const val CORRECTION_BUDGET_MS = 20_000L

        /** Потолок ответа при правке: реплика в 30 с — 25–40 токенов. */
        const val CORRECTION_MAX_TOKENS = 48

        /** Бюджет на один запрос анализа: весь диалог или его кусок. */
        const val ANALYSIS_BUDGET_MS = 45_000L

        /** Потолок ответа анализа: имена спикеров и резюме на 3–5 предложений. */
        const val ANALYSIS_MAX_TOKENS = 160

        /** Каталог с готовым комплектом ONNX GenAI или `null`, если модели нет. */
        fun modelDirectory(context: Context): File? =
            candidates(context).firstOrNull(LlmModelDirectory::isComplete)

        /** Есть ли на устройстве модель, с которой можно работать. */
        fun isAvailable(context: Context): Boolean = modelDirectory(context) != null

        /**
         * Корректор и анализатор на одной модели или `null`, если модели нет.
         *
         * @param lexicon пары «как услышали» → «как правильно» для слоя без модели.
         */
        fun create(context: Context, lexicon: Map<String, String> = emptyMap()): OnDeviceLlm? {
            val dir = modelDirectory(context) ?: return null
            val generator = OnnxGenAiGenerator(dir)
            val layers = buildList {
                if (lexicon.isNotEmpty()) add(LexiconCorrector(lexicon))
                add(LlmCorrector(generator, maxOutputTokens = CORRECTION_MAX_TOKENS, budgetMs = CORRECTION_BUDGET_MS))
            }
            return OnDeviceLlm(
                corrector = CompositeCorrector(layers),
                analyzer = LlmDialogueAnalyzer(
                    generator,
                    maxOutputTokens = ANALYSIS_MAX_TOKENS,
                    budgetMs = ANALYSIS_BUDGET_MS,
                ),
            )
        }

        /** Каталоги, где приложение ищет модель, в порядке приоритета. */
        fun candidates(context: Context): List<File> = buildList {
            add(File(context.filesDir, MODEL_DIR_NAME))
            // Внешний каталог доступен для `adb push` без run-as.
            context.getExternalFilesDir(null)?.let { add(File(it, MODEL_DIR_NAME)) }
        }
    }
}
