package com.example.llm

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.speech.impl.LlmCorrector
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Реальная проверка ONNX Runtime GenAI на устройстве.
 *
 * Модель не входит в APK и не пакуется в тест: тест пропускается, если её нет.
 * Залить: `llm/scripts/push-model.ps1 -Package com.example.llm` либо через
 * `-Pandroid.testInstrumentationRunnerArguments.modelDir=/sdcard/Download/onwin-llm`.
 */
@RunWith(AndroidJUnit4::class)
class OnnxGenAiGeneratorDeviceTest {

    @Test
    fun generatesCyrillicText_whenModelIsPushed() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val explicit = InstrumentationRegistry.getArguments().getString("modelDir")?.let(::File)
        val dir = explicit
            ?.takeIf(LlmModelDirectory::isComplete)
            ?: OnDeviceCorrector.modelDirectory(instrumentation.targetContext)
        assumeTrue("Модель не залита: llm/scripts/push-model.ps1", dir != null)

        val generator = OnnxGenAiGenerator(checkNotNull(dir))
        try {
            generator.load()
            val answer = generator.generate("Исправь ошибку распознавания: я придёт завтра.", 64)
            assertTrue("Пустой ответ модели", answer.isNotBlank())
            assertTrue(
                "Ответ без кириллицы — модель не та: $answer",
                answer.any { it in 'а'..'я' || it in 'А'..'Я' },
            )
        } finally {
            generator.release()
        }
    }

    /**
     * Полный стек коррекции: промпт, маски чисел, генерация и гард.
     * Возвращает текст, который увидит пользователь (лог — `LlmCorrectorTest`).
     */
    @Test
    fun llmCorrector_returnsTextThatPassesTheGuard() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = OnDeviceCorrector.modelDirectory(context)
        assumeTrue("Модель не залита: llm/scripts/push-model.ps1", dir != null)

        val corrector = LlmCorrector(
            OnnxGenAiGenerator(checkNotNull(dir)),
            maxOutputTokens = OnDeviceCorrector.MAX_OUTPUT_TOKENS,
            budgetMs = 20_000,
        )
        try {
            corrector.load()
            val source = "я придёт завтра в девять"
            val corrected = corrector.correct(source)
            android.util.Log.i("LlmCorrectorTest", "source=$source corrected=$corrected")
            assertFalse("Пустой текст после коррекции", corrected.isBlank())
        } finally {
            corrector.release()
        }
    }
}
